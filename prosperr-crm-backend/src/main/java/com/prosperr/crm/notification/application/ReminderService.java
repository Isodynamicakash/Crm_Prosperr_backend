package com.prosperr.crm.notification.application;

import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.shared.domain.Ist;
import com.prosperr.crm.shared.jdbc.Db;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Time-based notifications: meeting tomorrow, meeting without outcome, follow-ups due/overdue, daily digest. */
@Service
public class ReminderService {
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService n;
    private final ConfigApi config;

    public ReminderService(NamedParameterJdbcTemplate jdbc, NotificationService n, ConfigApi config) {
        this.jdbc = jdbc; this.n = n; this.config = config;
    }

    LocalDate today() { return LocalDate.ofInstant(n.now(), Ist.ZONE); }

    /** "Meeting tomorrow" -> AE and BDR. */
    @Transactional
    public int meetingsTomorrow() {
        LocalDate d = today().plusDays(1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.id, m.account_id, m.ae_id, m.booked_by, m.level, a.name FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id
                 WHERE m.status IN ('SCHEDULED','RESCHEDULED') AND m.outcome IS NULL
                   AND m.scheduled_start >= :from AND m.scheduled_start < :to""",
                new MapSqlParameterSource().addValue("from", Db.ts(d.atStartOfDay(Ist.ZONE).toInstant()))
                        .addValue("to", Db.ts(d.plusDays(1).atStartOfDay(Ist.ZONE).toInstant())));
        for (var r : rows) {
            n.notify(List.of((UUID) r.get("booked_by"), r.get("ae_id") == null ? (UUID) r.get("booked_by") : (UUID) r.get("ae_id")),
                    "MEETING_TOMORROW", r.get("level") + " tomorrow: " + r.get("name"), null, "meeting", (UUID) r.get("id"));
        }
        return rows.size();
    }

    /** "Meeting time passed with no outcome" -> AE (once a day per meeting). */
    @Transactional
    public int meetingsWithoutOutcome() {
        Instant now = n.now();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.id, coalesce(m.ae_id, m.booked_by) AS owner, m.level, a.name FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id
                 WHERE m.outcome IS NULL AND m.status IN ('SCHEDULED','RESCHEDULED') AND m.scheduled_start < :now""",
                new MapSqlParameterSource().addValue("now", Db.ts(now)));
        int sent = 0;
        for (var r : rows) {
            UUID owner = (UUID) r.get("owner");
            if (n.alreadySent(owner, "MEETING_NO_OUTCOME", (UUID) r.get("id"), now.minusSeconds(86400))) continue;
            n.notify(List.of(owner), "MEETING_NO_OUTCOME", "Record the outcome: " + r.get("level") + " at " + r.get("name"),
                    "Also update the next step and follow-up date.", "meeting", (UUID) r.get("id"));
            sent++;
        }
        return sent;
    }

    /** Follow-up due -> owner; overdue past the threshold -> owner's manager too. */
    @Transactional
    public int followUps() {
        LocalDate today = today();
        Instant start = today.atStartOfDay(Ist.ZONE).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(Ist.ZONE).toInstant();
        int days = config.number("overdue_manager_alert_days", 2);
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT t.id, t.assignee_id, t.title, t.due_at, u.manager_id FROM crm.task t JOIN crm.app_user u ON u.id = t.assignee_id
                 WHERE t.status = 'OPEN' AND t.due_at < :end""",
                new MapSqlParameterSource().addValue("end", Db.ts(end)));
        int count = 0;
        for (var r : due) {
            Instant at = ((java.sql.Timestamp) r.get("due_at")).toInstant();
            UUID owner = (UUID) r.get("assignee_id");
            boolean overdue = at.isBefore(start);
            String type = overdue ? "FOLLOW_UP_OVERDUE" : "FOLLOW_UP_DUE";
            if (n.alreadySent(owner, type, (UUID) r.get("id"), start)) continue;
            List<UUID> to = new ArrayList<>(List.of(owner));
            if (overdue && at.isBefore(start.minusSeconds(86400L * days)) && r.get("manager_id") != null) to.add((UUID) r.get("manager_id"));
            n.notify(to, type, (overdue ? "Overdue: " : "Due today: ") + r.get("title"), null, "task", (UUID) r.get("id"));
            count++;
        }
        return count;
    }

    /** Daily digest at 9:00 IST: today's tasks, meetings and overdue items for every active user. */
    @Transactional
    public int dailyDigest() {
        LocalDate today = today();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("start", Db.ts(today.atStartOfDay(Ist.ZONE).toInstant()))
                .addValue("end", Db.ts(today.plusDays(1).atStartOfDay(Ist.ZONE).toInstant()));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id,
                       (SELECT count(*) FROM crm.task t WHERE t.assignee_id = u.id AND t.status = 'OPEN' AND t.due_at >= :start AND t.due_at < :end) AS tasks,
                       (SELECT count(*) FROM crm.task t WHERE t.assignee_id = u.id AND t.status = 'OPEN' AND t.due_at < :start) AS overdue,
                       (SELECT count(*) FROM crm.meeting m WHERE (m.ae_id = u.id OR m.booked_by = u.id) AND m.status IN ('SCHEDULED','RESCHEDULED')
                          AND m.scheduled_start >= :start AND m.scheduled_start < :end) AS meetings
                  FROM crm.app_user u WHERE u.is_active""", p);
        for (var r : rows) {
            String body = r.get("tasks") + " tasks today, " + r.get("meetings") + " meetings, " + r.get("overdue") + " overdue";
            jdbc.update("""
                    INSERT INTO crm.notification (user_id, type, title, body, channel) VALUES (:u, 'DAILY_DIGEST', 'Your day', :b, 'DIGEST')""",
                    new MapSqlParameterSource().addValue("u", r.get("id")).addValue("b", body));
        }
        return rows.size();
    }
}
