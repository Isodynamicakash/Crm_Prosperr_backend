package com.prosperr.crm.integrations.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.assignment.api.AccountAssigned;
import com.prosperr.crm.pipeline.api.MeetingBooked;
import com.prosperr.crm.pipeline.api.MeetingsCancelled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Puts CRM meetings on Google Calendar: the person who booked (or else the AE) is the organiser, a Meet link is
 * created unless one was typed in, the team is invited, and clients only when GOOGLE_CALENDAR_INVITE_CLIENTS=true.
 * Runs after the booking commits and on a background thread, so it can never block or undo a booking.
 */
@Service
public class CalendarService {
    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);
    private static final String API = "https://www.googleapis.com/calendar/v3";

    private final GoogleAccountService google;
    private final NamedParameterJdbcTemplate jdbc;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "calendar-sync"); t.setDaemon(true); return t; });

    public CalendarService(GoogleAccountService google, NamedParameterJdbcTemplate jdbc) { this.google = google; this.jdbc = jdbc; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBooked(MeetingBooked e) {
        if (!google.available()) return;
        worker.submit(() -> safely(() -> sync(e.meetingId()), "sync meeting " + e.meetingId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAssigned(AccountAssigned e) {
        if (!google.available() || !"AE".equals(e.ownerType())) return;
        worker.submit(() -> safely(() -> addAeToOpenEvents(e.accountId()), "add AE to events of " + e.accountId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCancelled(MeetingsCancelled e) {
        if (!google.available()) return;
        worker.submit(() -> safely(() -> { for (UUID id : e.meetingIds()) cancel(id); }, "cancel events of " + e.accountId()));
    }

    /** Removes the Google event (attendees get a cancellation) when the CRM cancels the meeting. */
    void cancel(UUID meetingId) throws Exception {
        Row m = row(meetingId).orElse(null);
        if (m == null || m.eventId() == null || m.ownerId() == null) return;
        Optional<String> tok = google.accessToken(m.ownerId());
        if (tok.isEmpty()) return;
        try {
            google.call("DELETE", API + "/calendars/primary/events/" + m.eventId() + "?sendUpdates=all", tok.get(), null);
        } catch (GoogleAccountService.GoogleError ge) {
            if (ge.status != 404 && ge.status != 410) throw ge;   // already gone
        }
        jdbc.update("UPDATE crm.meeting SET calendar_event_id = NULL WHERE id = :id", Map.of("id", meetingId));
    }

    record Row(UUID id, UUID accountId, String accountName, String level, Instant start, Instant end, String link, UUID bookedBy,
               UUID aeId, String eventId, UUID ownerId, UUID rescheduledFrom) {}

    private Optional<Row> row(UUID meetingId) {
        return jdbc.query("""
                SELECT m.id, m.account_id, a.name, m.level, m.scheduled_start, m.scheduled_end, m.meeting_link, m.booked_by, m.ae_id,
                       m.calendar_event_id, m.calendar_owner_id, m.rescheduled_from_id
                  FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id WHERE m.id = :id""", Map.of("id", meetingId),
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                        rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getString(10), rs.getObject(11, UUID.class),
                        rs.getObject(12, UUID.class))).stream().findFirst();
    }

    void sync(UUID meetingId) throws Exception {
        Row m = row(meetingId).orElse(null);
        if (m == null || m.eventId() != null) return;
        Instant end = m.end() == null ? m.start().plusSeconds(30 * 60) : m.end();

        // Rescheduled: move the existing event instead of sending a second invite.
        if (m.rescheduledFrom() != null) {
            Row old = row(m.rescheduledFrom()).orElse(null);
            if (old != null && old.eventId() != null && old.ownerId() != null) {
                Optional<String> tok = google.accessToken(old.ownerId());
                if (tok.isPresent()) {
                    google.call("PATCH", API + "/calendars/primary/events/" + old.eventId() + "?sendUpdates=all", tok.get(),
                            Map.of("start", time(m.start()), "end", time(end)));
                    save(m.id(), old.eventId(), old.ownerId(), old.link());   // the Meet link moves with the event
                    jdbc.update("UPDATE crm.meeting SET calendar_event_id = NULL WHERE id = :id", Map.of("id", old.id()));
                    return;
                }
            }
        }

        UUID owner = google.connected(m.bookedBy()) ? m.bookedBy() : m.aeId() != null && google.connected(m.aeId()) ? m.aeId() : null;
        if (owner == null) return;
        Optional<String> tok = google.accessToken(owner);
        if (tok.isEmpty()) return;

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("summary", m.level() + " · Prosperr × " + m.accountName());
        ev.put("description", "Booked from Prosperr CRM." + (m.link() != null ? "\nJoin: " + m.link() : ""));
        ev.put("start", time(m.start()));
        ev.put("end", time(end));
        ev.put("attendees", attendees(m, owner));
        ev.put("reminders", Map.of("useDefault", true));
        String url = API + "/calendars/primary/events?sendUpdates=all";
        if (m.link() == null || m.link().isBlank()) {
            ev.put("conferenceData", Map.of("createRequest", Map.of("requestId", m.id().toString(),
                    "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));
            url += "&conferenceDataVersion=1";
        } else {
            ev.put("location", m.link());
        }
        JsonNode created = google.call("POST", url, tok.get(), ev);
        String meet = created.path("hangoutLink").asText(null);
        save(m.id(), created.path("id").asText(), owner, m.link() == null || m.link().isBlank() ? meet : null);
    }

    private void save(UUID meetingId, String eventId, UUID owner, String meetLink) {
        jdbc.update("""
                UPDATE crm.meeting SET calendar_event_id = :e, calendar_owner_id = :o,
                       meeting_link = coalesce(meeting_link, :l) WHERE id = :id""",
                new MapSqlParameterSource().addValue("e", eventId).addValue("o", owner).addValue("l", meetLink).addValue("id", meetingId));
    }

    void addAeToOpenEvents(UUID accountId) throws Exception {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM crm.meeting WHERE account_id = :a AND calendar_event_id IS NOT NULL AND outcome IS NULL
                   AND status IN ('SCHEDULED','RESCHEDULED')""", Map.of("a", accountId), UUID.class);
        for (UUID id : ids) {
            Row m = row(id).orElse(null);
            if (m == null || m.ownerId() == null) continue;
            Optional<String> tok = google.accessToken(m.ownerId());
            if (tok.isEmpty()) continue;
            google.call("PATCH", API + "/calendars/primary/events/" + m.eventId() + "?sendUpdates=all", tok.get(),
                    Map.of("attendees", attendees(m, m.ownerId())));
        }
    }

    /** Team members (their connected Google address, else CRM e-mail) and, only if allowed, the client stakeholders. */
    private List<Map<String, Object>> attendees(Row m, UUID owner) {
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        for (UUID u : new UUID[]{m.bookedBy(), m.aeId()}) {
            if (u == null || u.equals(owner)) continue;
            emails.add(google.email(u).orElseGet(() -> jdbc.queryForObject("SELECT email FROM crm.app_user WHERE id = :u", Map.of("u", u), String.class)));
        }
        if (google.inviteClients()) {
            emails.addAll(jdbc.queryForList("""
                    SELECT DISTINCT ON (se.stakeholder_id) se.email FROM crm.meeting_attendee ma
                      JOIN crm.stakeholder_email se ON se.stakeholder_id = ma.stakeholder_id AND se.is_valid
                     WHERE ma.meeting_id = :m ORDER BY se.stakeholder_id, se.is_primary DESC""", Map.of("m", m.id()), String.class));
        }
        return emails.stream().<Map<String, Object>>map(e -> Map.of("email", e)).toList();
    }

    public record Busy(UUID userId, String name, boolean connected, List<Map<String, String>> busy) {}

    /** Busy blocks (no titles or details) on the given day for each user, for picking a free slot. */
    public List<Busy> busy(List<UUID> userIds, LocalDate day) {
        Instant from = day.atStartOfDay(com.prosperr.crm.shared.domain.Ist.ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(com.prosperr.crm.shared.domain.Ist.ZONE).toInstant();
        List<Busy> out = new ArrayList<>();
        for (UUID u : new LinkedHashSet<>(userIds)) {
            String name = jdbc.queryForList("SELECT full_name FROM crm.app_user WHERE id = :u", Map.of("u", u), String.class).stream().findFirst().orElse("");
            Optional<String> tok = google.accessToken(u);
            if (tok.isEmpty()) { out.add(new Busy(u, name, false, List.of())); continue; }
            try {
                JsonNode r = google.call("POST", API + "/freeBusy", tok.get(), Map.of("timeMin", from.toString(), "timeMax", to.toString(),
                        "timeZone", "Asia/Kolkata", "items", List.of(Map.of("id", "primary"))));
                List<Map<String, String>> blocks = new ArrayList<>();
                r.path("calendars").path("primary").path("busy").forEach(b -> blocks.add(Map.of("start", b.path("start").asText(), "end", b.path("end").asText())));
                out.add(new Busy(u, name, true, blocks));
            } catch (Exception e) {
                log.warn("free/busy failed for {}: {}", u, e.toString());
                out.add(new Busy(u, name, true, List.of()));
            }
        }
        return out;
    }

    private static Map<String, String> time(Instant t) { return Map.of("dateTime", t.toString(), "timeZone", "Asia/Kolkata"); }

    private void safely(ThrowingRunnable r, String what) {
        try { r.run(); }
        catch (GoogleAccountService.GoogleError e) {
            log.warn("Calendar {} failed: {}", what, e.getMessage());
        } catch (Exception e) {
            log.warn("Calendar {} failed", what, e);
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }
}
