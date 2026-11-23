package com.prosperr.crm.reporting.application;

import com.prosperr.crm.shared.domain.Ist;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/**
 * Team activity for the public sign-in page. Only totals and the kind/time of recent events: no company, person
 * or deal names, because the page is visible to anyone. Cached for a minute so the public route stays cheap.
 */
@Service
public class PublicStatsService {
    public record Kpis(long calls, long sqls, long meetings) {}
    public record Event(String kind, Instant at) {}
    public record Stats(String period, Kpis kpis, List<Event> recent, Instant generatedAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private volatile Stats cached;

    public PublicStatsService(NamedParameterJdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Stats stats() {
        Stats c = cached;
        Instant now = clock.instant();
        if (c != null && c.generatedAt().isAfter(now.minusSeconds(60))) return c;
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        // Show the shortest period that has something in it, so a quiet morning doesn't read as all zeros.
        Stats s = null;
        for (var p : List.of(Map.entry("TODAY", today), Map.entry("WEEK", today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))),
                Map.entry("MONTH", today.withDayOfMonth(1)))) {
            Kpis k = kpis(p.getValue().atStartOfDay(Ist.ZONE).toInstant(), now);
            if (k.calls() + k.sqls() + k.meetings() > 0 || p.getKey().equals("MONTH")) { s = new Stats(p.getKey(), k, recent(now), now); break; }
        }
        cached = s;
        return s;
    }

    private Kpis kpis(Instant from, Instant now) {
        var p = new MapSqlParameterSource("from", java.sql.Timestamp.from(from)).addValue("now", java.sql.Timestamp.from(now));
        long calls = jdbc.queryForObject("SELECT count(*) FROM crm.activity WHERE type = 'CALL' AND occurred_at >= :from AND occurred_at <= :now", p, Long.class);
        long sqls = jdbc.queryForObject("SELECT count(*) FROM crm.stage_history WHERE to_stage = 'SQL' AND changed_at >= :from AND changed_at <= :now", p, Long.class);
        long meetings = jdbc.queryForObject("SELECT count(*) FROM crm.meeting WHERE booked_on >= :from AND booked_on <= :now", p, Long.class);
        return new Kpis(calls, sqls, meetings);
    }

    /** The six most recent team events, as a kind and a time only. */
    private List<Event> recent(Instant now) {
        return jdbc.query("""
                SELECT kind, at FROM (
                  SELECT CASE WHEN o.code = 'L1_BOOKED' THEN 'CALL_L1' ELSE 'CALL' END AS kind, a.occurred_at AS at
                    FROM crm.activity a LEFT JOIN crm.picklist_value o ON o.id = a.call_outcome_id
                   WHERE a.type = 'CALL' AND a.occurred_at <= :now
                  UNION ALL
                  SELECT 'STAGE_' || to_stage, changed_at FROM crm.stage_history
                   WHERE to_stage IN ('SQL','L1','L2','L3','NEGOTIATION','WON') AND changed_at <= :now
                  UNION ALL
                  SELECT 'ASSIGNED', assigned_at FROM crm.assignment_history WHERE owner_type = 'AE' AND assigned_at <= :now
                  UNION ALL
                  SELECT 'TASK', created_at FROM crm.task WHERE source = 'SYSTEM' AND created_at <= :now
                ) e ORDER BY at DESC LIMIT 6""", Map.of("now", java.sql.Timestamp.from(now)),
                (rs, i) -> new Event(rs.getString("kind"), rs.getTimestamp("at").toInstant()));
    }
}
