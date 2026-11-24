package com.prosperr.crm.leadcapture.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.StakeholderApi;
import com.prosperr.crm.account.api.StakeholderView;
import com.prosperr.crm.account.domain.Normalizer;
import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.leadcapture.api.LeadCaptured;
import com.prosperr.crm.outreach.api.MailGateway;
import com.prosperr.crm.prospecting.api.ActivityApi;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Event QR lead capture. The public form never says whether someone is already in the CRM (no data leaks to the
 * visitor); the BDR sees the outcome in the CRM.
 */
@Service
public class LeadCaptureService {
    private static final Logger log = LoggerFactory.getLogger(LeadCaptureService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    static final Pattern FREE_MAIL = Pattern.compile(".*@(gmail|yahoo|hotmail|outlook|live|rediffmail|icloud|proton(mail)?|ymail)\\..*");
    static final int PER_IP_LIMIT = 6;                         // submissions per IP in 10 minutes
    static final Duration PER_IP_WINDOW = Duration.ofMinutes(10);

    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final StakeholderApi stakeholders;
    private final ActivityApi activities;
    private final TaskApi tasks;
    private final UserDirectory users;
    private final MailGateway mail;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public LeadCaptureService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, StakeholderApi stakeholders, ActivityApi activities,
                              TaskApi tasks, UserDirectory users, MailGateway mail, ApplicationEventPublisher events,
                              PlatformTransactionManager txm, Clock clock) {
        this.jdbc = jdbc; this.accounts = accounts; this.stakeholders = stakeholders; this.activities = activities; this.tasks = tasks;
        this.users = users; this.mail = mail; this.events = events; this.tx = new TransactionTemplate(txm); this.clock = clock;
    }

    // ------------------------------------------------------------------ the BDR's QR codes

    public record Qr(UUID id, String token, String eventName, boolean sendWelcome, boolean active, Instant createdAt,
                     UUID ownerId, String ownerName, int captured, int created, int existing, int review) {}

    public List<Qr> list(Actor actor) {
        return jdbc.query("""
                SELECT q.*, u.full_name AS owner_name,
                       count(c.id) AS captured, count(c.id) FILTER (WHERE c.outcome = 'CREATED') AS created,
                       count(c.id) FILTER (WHERE c.outcome = 'EXISTING') AS existing, count(c.id) FILTER (WHERE c.outcome = 'REVIEW') AS review
                  FROM crm.lead_qr q JOIN crm.app_user u ON u.id = q.owner_id LEFT JOIN crm.lead_capture c ON c.qr_id = q.id
                 WHERE (:all OR q.owner_id = :me)
                 GROUP BY q.id, u.full_name ORDER BY q.active DESC, q.created_at DESC""",
                new MapSqlParameterSource().addValue("all", actor.isLeadership()).addValue("me", actor.userId()),
                (rs, i) -> new Qr(rs.getObject("id", UUID.class), rs.getString("token"), rs.getString("event_name"), rs.getBoolean("send_welcome"),
                        rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(), rs.getObject("owner_id", UUID.class),
                        rs.getString("owner_name"), rs.getInt("captured"), rs.getInt("created"), rs.getInt("existing"), rs.getInt("review")));
    }

    public UUID create(String eventName, boolean sendWelcome, Actor actor) {
        String name = Normalizer.clean(eventName).orElseThrow(() -> new DomainException("EVENT_REQUIRED", "Give the event a name, for example 'HR Summit Mumbai'."));
        if (name.length() > 80) throw new DomainException("EVENT_TOO_LONG", "Keep the event name under 80 characters.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO crm.lead_qr (id, token, owner_id, event_name, send_welcome) VALUES (:id, :t, :o, :n, :w)",
                new MapSqlParameterSource().addValue("id", id).addValue("t", token()).addValue("o", actor.userId())
                        .addValue("n", name).addValue("w", sendWelcome));
        return id;
    }

    public void update(UUID id, Boolean active, Boolean sendWelcome, Actor actor) {
        owned(id, actor);
        jdbc.update("UPDATE crm.lead_qr SET active = coalesce(:a, active), send_welcome = coalesce(:w, send_welcome) WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("a", active).addValue("w", sendWelcome));
    }

    public List<Map<String, Object>> captures(UUID id, Actor actor) {
        owned(id, actor);
        return jdbc.queryForList("""
                SELECT c.id, c.full_name AS "fullName", c.company, c.designation, c.email, c.phone, c.outcome, c.review_reason AS "reviewReason",
                       c.welcome_sent AS "welcomeSent", c.created_at AS "createdAt", c.account_id AS "accountId", c.stakeholder_id AS "stakeholderId",
                       a.name AS "accountName"
                  FROM crm.lead_capture c LEFT JOIN crm.account a ON a.id = c.account_id WHERE c.qr_id = :id ORDER BY c.created_at DESC""",
                Map.of("id", id));
    }

    private void owned(UUID id, Actor actor) {
        UUID owner = jdbc.query("SELECT owner_id FROM crm.lead_qr WHERE id = :id", Map.of("id", id),
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (owner == null || (!owner.equals(actor.userId()) && !actor.isLeadership())) throw new NotFoundException("QR code", id);
    }

    private String token() {
        StringBuilder b = new StringBuilder(10);
        for (int i = 0; i < 10; i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return b.toString();
    }

    // ------------------------------------------------------------------ public form

    record QrRow(UUID id, UUID ownerId, String eventName, boolean sendWelcome) {}

    private QrRow active(String token) {
        return jdbc.query("SELECT q.id, q.owner_id, q.event_name, q.send_welcome FROM crm.lead_qr q JOIN crm.app_user u ON u.id = q.owner_id " +
                          "WHERE q.token = :t AND q.active AND u.is_active", Map.of("t", token == null ? "" : token),
                (rs, i) -> new QrRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getBoolean(4)))
                .stream().findFirst().orElseThrow(() -> new NotFoundException("QR code", token));
    }

    /** What the form shows: the event and the BDR's first name only. */
    public Map<String, Object> describe(String token) {
        QrRow q = active(token);
        String first = users.get(q.ownerId()).fullName().split("\\s+")[0];
        return Map.of("eventName", q.eventName(), "repName", first);
    }

    public record Submission(String fullName, String company, String designation, String email, String phone, boolean consent, String website) {}

    /** Validates, then creates or matches the contact. Returns normally for every accepted form, whatever the outcome. */
    public void submit(String token, Submission s, String clientIp) {
        QrRow q = active(token);
        if (s.website() != null && !s.website().isBlank()) { log.info("QR {}: honeypot filled, ignored", q.id()); return; }  // bots fill every field
        throttle(clientIp);
        String name = required(s.fullName(), "your name", 2, 80);
        String company = required(s.company(), "your company", 2, 120);
        String designation = Normalizer.clean(s.designation()).map(d -> d.length() > 100 ? d.substring(0, 100) : d).orElse(null);
        String email = Normalizer.email(s.email()).orElseThrow(() -> new DomainException("INVALID_EMAIL", "Enter a valid email address."));
        String phone = null;
        if (s.phone() != null && !s.phone().isBlank())
            phone = Normalizer.phoneE164(s.phone()).orElseThrow(() -> new DomainException("INVALID_PHONE", "Enter a valid phone number, or leave it blank."));
        if (!s.consent()) throw new DomainException("CONSENT_REQUIRED", "Please tick the box so we can contact you.");

        // the same person submitting twice (double tap, or coming back) is one lead
        Boolean again = jdbc.queryForObject("SELECT exists (SELECT 1 FROM crm.lead_capture WHERE qr_id = :q AND email = :e)",
                Map.of("q", q.id(), "e", email), Boolean.class);
        if (Boolean.TRUE.equals(again)) return;

        UserView owner = users.get(q.ownerId());
        Actor actor = new Actor(owner.id(), owner.role());
        Instant now = clock.instant();
        final String ph = phone;
        Outcome o;
        try {
            o = tx.execute(st -> capture(q, actor, name, company, designation, email, ph, now));
        } catch (RuntimeException e) {       // e.g. the company is in an AE's pipeline: keep the lead, let the BDR decide
            log.info("QR {}: lead needs review: {}", q.id(), e.getMessage());
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            o = tx.execute(st -> review(q, name, company, designation, email, ph, now, reason));
        }
        if (o != null && o.welcomeTo() != null) welcome(q, owner, o, name, email);
    }

    record Outcome(UUID captureId, String welcomeTo) {}

    private Outcome capture(QrRow q, Actor actor, String name, String company, String designation, String email, String phone, Instant now) {
        String event = q.eventName();
        // 1. already in the CRM? match by email, then by phone: never a second record
        Optional<UUID> existing = jdbc.query("SELECT stakeholder_id FROM crm.stakeholder_email WHERE email_normalized = :e LIMIT 1",
                Map.of("e", email), (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst();
        if (existing.isEmpty() && phone != null) existing = jdbc.query(
                "SELECT stakeholder_id FROM crm.stakeholder_phone WHERE right(phone_e164, 10) = right(:p, 10) LIMIT 1",
                Map.of("p", phone), (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst();
        if (existing.isPresent()) {
            StakeholderView sv = stakeholders.get(existing.get());
            UUID followUp = sv.bdrOwnerId() != null ? sv.bdrOwnerId() : actor.userId();
            activities.log(new ActivityApi.NewActivity(sv.accountId(), sv.id(), null, null, null, null, ActivityType.NOTE, null,
                    "Met at " + event + ": scanned " + firstName(actor.userId()) + "'s QR and shared their details again"
                            + (designation != null ? " (" + designation + ")" : "") + ".", now, actor.userId()));
            task(sv.accountId(), sv.id(), followUp, name, event, actor.userId(), now);
            UUID id = insertCapture(q, sv.accountId(), sv.id(), name, company, designation, email, phone, "EXISTING", null, now);
            events.publishEvent(new LeadCaptured(actor.userId(), sv.accountId(), name, company, event, "EXISTING"));
            return new Outcome(id, null);         // they already hear from us: no automatic email
        }

        // 2. new contact: join the company if it exists (name or work-email domain), else create it with lead source Event
        String website = FREE_MAIL.matcher(email).matches() ? null : email.substring(email.indexOf('@') + 1);
        UUID eventSource = jdbc.query("SELECT id FROM crm.picklist_value WHERE picklist_key = 'LEAD_SOURCE' AND code = 'EVENT'",
                Map.of(), (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst().orElse(null);
        AccountApi.Resolution r = accounts.resolveOrCreate(new AccountApi.CreateAccount(company, website, null, null, null,
                null, null, null, eventSource, null), actor);
        UUID bdr = actor.role() == Role.BDR ? actor.userId() : null;
        StakeholderView sv = stakeholders.create(new StakeholderApi.CreateStakeholder(r.account().id(), null, null, name, designation,
                null, null, List.of(email), phone == null ? List.of() : List.of(phone), null, null, null, bdr, false, false, true), actor);
        activities.log(new ActivityApi.NewActivity(r.account().id(), sv.id(), null, null, null, null, ActivityType.NOTE, null,
                "Met at " + event + ": filled the QR form and agreed to be contacted.", now, actor.userId()));
        task(r.account().id(), sv.id(), actor.userId(), name, event, actor.userId(), now);
        UUID id = insertCapture(q, r.account().id(), sv.id(), name, company, designation, email, phone, "CREATED", null, now);
        events.publishEvent(new LeadCaptured(actor.userId(), r.account().id(), name, company, event, "CREATED"));
        return new Outcome(id, q.sendWelcome() ? email : null);
    }

    private Outcome review(QrRow q, String name, String company, String designation, String email, String phone, Instant now, String reason) {
        UUID id = insertCapture(q, null, null, name, company, designation, email, phone, "REVIEW", reason, now);
        events.publishEvent(new LeadCaptured(q.ownerId(), null, name, company, q.eventName(), "REVIEW"));
        return new Outcome(id, null);
    }

    private UUID insertCapture(QrRow q, UUID account, UUID stakeholder, String name, String company, String designation, String email,
                               String phone, String outcome, String reason, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.lead_capture (id, qr_id, account_id, stakeholder_id, full_name, company, designation, email, phone, outcome, review_reason, consent_at)
                VALUES (:id, :q, :a, :s, :n, :c, :d, :e, :p, :o, :r, :at)""",
                new MapSqlParameterSource().addValue("id", id).addValue("q", q.id()).addValue("a", account).addValue("s", stakeholder)
                        .addValue("n", name).addValue("c", company).addValue("d", designation).addValue("e", email).addValue("p", phone)
                        .addValue("o", outcome).addValue("r", reason).addValue("at", java.sql.Timestamp.from(now)));
        return id;
    }

    private void task(UUID account, UUID stakeholder, UUID assignee, String name, String event, UUID by, Instant now) {
        tasks.create(new TaskApi.NewTask(account, stakeholder, null, null, TaskType.FOLLOW_UP,
                "Follow up with " + name + " (met at " + event + ")", nextWorkingMorning(now), assignee, TaskSource.SYSTEM, null, by));
    }

    /** 11:00 IST on the next weekday. */
    static Instant nextWorkingMorning(Instant now) {
        LocalDate d = now.atZone(IST).toLocalDate().plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d.atTime(11, 0).atZone(IST).toInstant();
    }

    private String firstName(UUID userId) { return users.get(userId).fullName().split("\\s+")[0]; }

    /** Optional "nice to meet you" from the BDR's own Gmail. A send failure never loses the lead. */
    private void welcome(QrRow q, UserView owner, Outcome o, String name, String email) {
        String from = jdbc.query("SELECT gmail_address FROM crm.user_mailbox WHERE user_id = :u AND status = 'CONNECTED'",
                Map.of("u", owner.id()), (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        if (from == null) return;
        String first = name.split("\\s+")[0];
        String body = "Hi " + first + ",\n\nGreat to meet you at " + q.eventName() + ". Thanks for sharing your details.\n\n"
                + "Prosperr helps companies run a compliant Flexible Benefits Plan that increases employees' in-hand salary without increasing payroll cost. "
                + "I'll call you in the next few days to see if it fits your team, or just reply here with a time that suits you.\n\n"
                + "Best,\n" + owner.fullName() + "\nProsperr" + (owner.phone() == null || owner.phone().isBlank() ? "" : "\n" + owner.phone());
        try {
            mail.send(new MailGateway.OutgoingMail(owner.id(), from, email, "Great meeting you at " + q.eventName(), body, null));
            jdbc.update("UPDATE crm.lead_capture SET welcome_sent = true WHERE id = :id", Map.of("id", o.captureId()));
        } catch (RuntimeException e) {
            log.warn("QR {}: welcome email to lead failed: {}", q.id(), e.getMessage());
        }
    }

    private static String required(String v, String what, int min, int max) {
        String c = Normalizer.clean(v).orElse("");
        if (c.length() < min) throw new DomainException("FIELD_REQUIRED", "Please enter " + what + ".");
        if (c.length() > max) throw new DomainException("FIELD_TOO_LONG", "That is too long for " + what + ".");
        return c;
    }

    /** Simple per-IP limit so one person cannot flood a BDR's list. */
    void throttle(String ip) {
        Instant now = clock.instant();
        Deque<Instant> d = hits.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst().isBefore(now.minus(PER_IP_WINDOW))) d.pollFirst();
            if (d.size() >= PER_IP_LIMIT) throw new DomainException("TOO_MANY", "Too many forms from this device. Please try again in a few minutes.");
            d.addLast(now);
        }
        if (hits.size() > 10_000) hits.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
