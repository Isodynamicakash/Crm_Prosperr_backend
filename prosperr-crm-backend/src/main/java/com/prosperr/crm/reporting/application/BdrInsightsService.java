package com.prosperr.crm.reporting.application;

import com.prosperr.crm.reporting.api.PerformanceApi;
import com.prosperr.crm.shared.domain.Ist;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * One BDR's own book: the "My leads" page (where every lead stands, where they came from, what needs attention) and
 * the numbers behind the voice coach (results against target and pace, compared with the team's average).
 */
@Service
@Transactional(readOnly = true)
public class BdrInsightsService implements PerformanceApi {
    private final NamedParameterJdbcTemplate jdbc;
    private final DashboardService dashboards;
    private final Clock clock;

    public BdrInsightsService(NamedParameterJdbcTemplate jdbc, DashboardService dashboards, Clock clock) {
        this.jdbc = jdbc; this.dashboards = dashboards; this.clock = clock;
    }

    static final int MIN_CALLS = 30;
    private static final String MY_ACCOUNTS = "EXISTS (SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = a.id AND ab.bdr_id = :u)";

    // ------------------------------------------------------------------ My leads page

    public Map<String, Object> leads(UUID bdrId) {
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        var p = new MapSqlParameterSource().addValue("u", bdrId).addValue("connects", DashboardService.CONNECT_CODES)
                .addValue("since", java.sql.Timestamp.from(today.minusWeeks(11).with(DayOfWeek.MONDAY).atStartOfDay(Ist.ZONE).toInstant()))
                .addValue("stale", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", jdbc.queryForObject("SELECT full_name FROM crm.app_user WHERE id = :u", p, String.class));

        m.put("totals", jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM crm.account a WHERE %1$s) AS accounts,
                       (SELECT count(*) FROM crm.account a WHERE %1$s AND a.account_status = 'ACTIVE' AND a.visibility_zone = 'PROSPECTING') AS prospecting,
                       (SELECT count(*) FROM crm.account a WHERE %1$s AND a.visibility_zone = 'PIPELINE' AND a.lifecycle_stage NOT IN ('WON','LOST','IMPLEMENTATION')) AS "inPipeline",
                       (SELECT count(*) FROM crm.account a WHERE %1$s AND a.lifecycle_stage IN ('WON','IMPLEMENTATION')) AS won,
                       (SELECT count(*) FROM crm.stakeholder s WHERE s.bdr_owner_id = :u) AS contacts,
                       (SELECT count(*) FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id
                         WHERE s.bdr_owner_id = :u AND s.engagement_status = 'NOT_CONTACTED' AND NOT s.do_not_contact AND a.account_status = 'ACTIVE') AS "neverCalled",
                       (SELECT count(*) FROM crm.account a WHERE %1$s AND a.account_status = 'ACTIVE' AND a.visibility_zone = 'PROSPECTING'
                           AND coalesce(a.last_activity_at, a.created_at) < :stale) AS stale,
                       (SELECT count(*) FROM crm.task t WHERE t.assignee_id = :u AND t.status = 'OPEN' AND t.due_at < now()) AS overdue""".formatted(MY_ACCOUNTS), p));

        // contacts by where they stand, in funnel order
        m.put("contactFunnel", jdbc.queryForList("""
                SELECT s.engagement_status AS status, count(*) AS count FROM crm.stakeholder s WHERE s.bdr_owner_id = :u GROUP BY 1""", p));
        m.put("accountStages", jdbc.queryForList("""
                SELECT a.lifecycle_stage AS stage, count(*) AS count FROM crm.account a WHERE %s GROUP BY 1""".formatted(MY_ACCOUNTS), p));
        m.put("bySource", jdbc.queryForList("""
                SELECT coalesce(pv.label, 'Not set') AS label, count(*) AS count FROM crm.account a
                  LEFT JOIN crm.picklist_value pv ON pv.id = a.lead_source_id WHERE %s GROUP BY 1 ORDER BY 2 DESC""".formatted(MY_ACCOUNTS), p));
        m.put("byCityTier", jdbc.queryForList("""
                SELECT CASE WHEN c.icp_priority IN ('P0','P1') THEN c.icp_priority WHEN c.id IS NULL THEN 'Not set' ELSE 'Other cities' END AS label,
                       count(*) AS count FROM crm.account a LEFT JOIN crm.city c ON c.id = a.city_id WHERE %s GROUP BY 1 ORDER BY 1""".formatted(MY_ACCOUNTS), p));
        m.put("bySize", jdbc.queryForList("""
                SELECT coalesce(a.size_bucket_code, 'UNKNOWN') AS label, count(*) AS count FROM crm.account a WHERE %s GROUP BY 1""".formatted(MY_ACCOUNTS), p));
        m.put("byLevel", jdbc.queryForList("""
                SELECT coalesce('L' || s.level, 'Unmapped') AS label, count(*) AS count FROM crm.stakeholder s WHERE s.bdr_owner_id = :u GROUP BY 1 ORDER BY 1""", p));
        m.put("byFunction", jdbc.queryForList("""
                SELECT coalesce(s.department, 'OTHER') AS label, count(*) AS count FROM crm.stakeholder s WHERE s.bdr_owner_id = :u GROUP BY 1 ORDER BY 2 DESC""", p));

        // the last 12 weeks: calls, conversations, L1s booked
        m.put("weekly", jdbc.queryForList("""
                SELECT to_char(w.wk, 'YYYY-MM-DD') AS week,
                       (SELECT count(*) FROM crm.activity x WHERE x.type = 'CALL' AND x.logged_by = :u
                           AND x.occurred_at >= w.wk AND x.occurred_at < w.wk + interval '7 days') AS calls,
                       (SELECT count(*) FROM crm.activity x JOIN crm.picklist_value pv ON pv.id = x.call_outcome_id
                         WHERE x.type = 'CALL' AND x.logged_by = :u AND pv.code IN (:connects)
                           AND x.occurred_at >= w.wk AND x.occurred_at < w.wk + interval '7 days') AS connects,
                       (SELECT count(*) FROM crm.meeting mt WHERE mt.level = 'L1' AND mt.booked_by = :u AND mt.rescheduled_from_id IS NULL
                           AND mt.booked_on >= w.wk AND mt.booked_on < w.wk + interval '7 days') AS "l1Booked"
                  FROM generate_series(CAST(:since AS timestamptz), now(), interval '7 days') AS w(wk) ORDER BY 1""", p));

        // what needs attention: hot or high-intent companies gone quiet, then the oldest untouched
        m.put("attention", jdbc.queryForList("""
                SELECT a.id, a.name, a.lifecycle_stage AS stage, a.intent_rating AS intent, c.name AS city,
                       to_char(coalesce(a.last_activity_at, a.created_at) AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD') AS "lastTouch",
                       (SELECT count(*) FROM crm.stakeholder s WHERE s.account_id = a.id AND s.bdr_owner_id = :u) AS contacts,
                       CASE WHEN a.intent_rating = 'HIGH' THEN 'High intent, gone quiet'
                            WHEN c.icp_priority = 'P0' AND coalesce(a.license_potential, 0) > 250 THEN 'Best-fit company (P0 city, 250+ licences)'
                            ELSE 'No touch in 30+ days' END AS reason
                  FROM crm.account a LEFT JOIN crm.city c ON c.id = a.city_id
                 WHERE %s AND a.account_status = 'ACTIVE' AND a.visibility_zone = 'PROSPECTING' AND coalesce(a.last_activity_at, a.created_at) < :stale
                 ORDER BY (a.intent_rating = 'HIGH') DESC NULLS LAST, (c.icp_priority = 'P0' AND coalesce(a.license_potential, 0) > 250) DESC NULLS LAST,
                          coalesce(a.license_potential, 0) DESC, coalesce(a.last_activity_at, a.created_at) LIMIT 8""".formatted(MY_ACCOUNTS), p));
        return m;
    }

    // ------------------------------------------------------------------ voice coach

    @Override
    public Map<String, Object> performance(UUID bdrId, String preset, LocalDate from, LocalDate to) {
        LocalDate[] r = dashboards.range(preset, from, to);
        LocalDate[] tw = dashboards.targetWindow(r);
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bdr", jdbc.queryForObject("SELECT split_part(full_name, ' ', 1) FROM crm.app_user WHERE id = :u", Map.of("u", bdrId), String.class));
        boolean running = !r[1].isBefore(today.plusDays(1)) && tw[1].isAfter(today.plusDays(1));
        long total = ChronoUnit.DAYS.between(tw[0], tw[1]), elapsed = ChronoUnit.DAYS.between(tw[0], running ? today.plusDays(1) : tw[1]);
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("from", r[0]); period.put("to", r[1].minusDays(1));
        period.put("running", running);
        period.put("shareElapsed", total == 0 ? 1.0 : Math.round(100.0 * elapsed / total) / 100.0);
        period.put("workingDaysLeft", running ? workingDays(today, tw[1]) : 0);
        f.put("period", period);

        List<Map<String, Object>> rows = dashboards.bdrRows(null, r, true);
        Map<String, Object> me = rows.stream().filter(x -> bdrId.equals(x.get("userId"))).findFirst().orElse(Map.of());
        f.put("me", me);

        var p = new MapSqlParameterSource().addValue("u", bdrId).addValue("from", r[0]).addValue("to", r[1]).addValue("connects", DashboardService.CONNECT_CODES);
        String inRange = " AND (x.occurred_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (x.occurred_at AT TIME ZONE 'Asia/Kolkata')::date < :to";
        // calls and conversations per BDR in the period, for "you vs the team"
        List<Map<String, Object>> team = jdbc.queryForList("""
                SELECT u.id, count(x.id) AS calls, count(x.id) FILTER (WHERE pv.code IN (:connects)) AS connects,
                       count(x.id) FILTER (WHERE pv.code IN ('DID_NOT_PICK','SWITCH_OFF','INCOMING_BARRED')) AS "noAnswer",
                       count(x.id) FILTER (WHERE pv.code = 'L1_BOOKED') AS "l1FromCalls",
                       count(x.id) FILTER (WHERE s.level <= 2) AS "seniorCalls"
                  FROM crm.app_user u LEFT JOIN crm.activity x ON x.logged_by = u.id AND x.type = 'CALL'""" + inRange + """
                  LEFT JOIN crm.picklist_value pv ON pv.id = x.call_outcome_id LEFT JOIN crm.stakeholder s ON s.id = x.stakeholder_id
                 WHERE u.role = 'BDR' AND u.is_active GROUP BY u.id""", p);
        Map<String, Object> mine = team.stream().filter(x -> bdrId.equals(x.get("id"))).findFirst().orElse(Map.of("calls", 0L, "connects", 0L, "noAnswer", 0L, "l1FromCalls", 0L, "seniorCalls", 0L));
        // only BDRs with enough logged calls make a fair comparison
        List<Map<String, Object>> others = team.stream().filter(x -> !bdrId.equals(x.get("id")) && n(x.get("calls")) >= MIN_CALLS).toList();
        long days = Math.max(1, workingDays(r[0], running ? today.plusDays(1) : r[1]));
        Map<String, Object> calls = new LinkedHashMap<>();
        long c = n(mine.get("calls")), con = n(mine.get("connects"));
        calls.put("calls", c);
        calls.put("callsPerDay", round1((double) c / days));
        calls.put("connects", con);
        calls.put("connectRate", c == 0 ? null : Math.round(100.0 * con / c));
        calls.put("noAnswerShare", c == 0 ? null : Math.round(100.0 * n(mine.get("noAnswer")) / c));
        calls.put("seniorShare", c == 0 ? null : Math.round(100.0 * n(mine.get("seniorCalls")) / c));
        calls.put("connectsPerL1", n(mine.get("l1FromCalls")) == 0 ? null : round1((double) con / n(mine.get("l1FromCalls"))));
        f.put("calls", calls);

        Map<String, Object> avg = new LinkedHashMap<>();
        avg.put("bdrsCompared", others.size());
        if (others.size() >= 2) {
            long tc = others.stream().mapToLong(x -> n(x.get("calls"))).sum(), tcon = others.stream().mapToLong(x -> n(x.get("connects"))).sum();
            long tl1 = others.stream().mapToLong(x -> n(x.get("l1FromCalls"))).sum();
            avg.put("callsPerDay", round1((double) tc / others.size() / days));
            avg.put("connectRate", tc == 0 ? null : Math.round(100.0 * tcon / tc));
            avg.put("connectsPerL1", tl1 == 0 ? null : round1((double) tcon / tl1));
            List<Long> l1 = rows.stream().filter(x -> others.stream().anyMatch(o -> o.get("id").equals(x.get("userId")))).map(x -> n(x.get("l1Done"))).toList();
            avg.put("l1Done", l1.isEmpty() ? null : round1(l1.stream().mapToLong(Long::longValue).average().orElse(0)));
        }
        f.put("teamAverage", avg);
        long myL1 = n(me.get("l1Done"));
        f.put("rankByL1Done", 1 + rows.stream().filter(x -> n(x.get("l1Done")) > myL1).count());
        f.put("bdrsRanked", rows.size());

        // best and worst hour to reach people, from the last 120 days of the BDR's own calls (at least 8 calls in the hour)
        f.put("hours", jdbc.queryForList("""
                SELECT extract(hour FROM x.occurred_at AT TIME ZONE 'Asia/Kolkata')::int AS hour, count(*) AS calls,
                       round(100.0 * count(*) FILTER (WHERE pv.code IN (:connects)) / count(*)) AS "connectRate"
                  FROM crm.activity x JOIN crm.picklist_value pv ON pv.id = x.call_outcome_id
                 WHERE x.type = 'CALL' AND x.logged_by = :u AND x.occurred_at > now() - interval '120 days'
                   AND coalesce(x.remarks, '') NOT LIKE 'Imported%'   -- imported calls have no real time of day
                 GROUP BY 1 HAVING count(*) >= 8 ORDER BY 3 DESC""", p));

        f.put("sql", jdbc.queryForMap("""
                SELECT count(*) AS submitted, count(*) FILTER (WHERE status = 'QUALIFIED_SQL') AS approved,
                       count(*) FILTER (WHERE status = 'REJECTED_NOT_ICP') AS rejected,
                       (SELECT pv.label FROM crm.sql_submission s2 JOIN crm.picklist_value pv ON pv.id = s2.rejection_reason_id
                         WHERE s2.submitted_by = :u AND (s2.submitted_at AT TIME ZONE 'Asia/Kolkata')::date >= :from
                           AND (s2.submitted_at AT TIME ZONE 'Asia/Kolkata')::date < :to GROUP BY pv.label ORDER BY count(*) DESC LIMIT 1) AS "topRejection"
                  FROM crm.sql_submission s WHERE s.submitted_by = :u
                   AND (s.submitted_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (s.submitted_at AT TIME ZONE 'Asia/Kolkata')::date < :to""", p));
        f.put("noShows", jdbc.queryForObject("""
                SELECT count(*) FROM crm.meeting mt WHERE mt.level = 'L1' AND mt.booked_by = :u AND mt.status = 'NO_SHOW'
                   AND (mt.scheduled_start AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (mt.scheduled_start AT TIME ZONE 'Asia/Kolkata')::date < :to""", p, Long.class));
        Map<String, Object> now = leadsNow(bdrId);
        f.put("now", now);
        return f;
    }

    private Map<String, Object> leadsNow(UUID bdrId) {
        var p = new MapSqlParameterSource().addValue("u", bdrId).addValue("stale", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM crm.task t WHERE t.assignee_id = :u AND t.status = 'OPEN' AND t.due_at < now()) AS "overdueFollowUps",
                       (SELECT count(*) FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id
                         WHERE s.bdr_owner_id = :u AND s.engagement_status = 'NOT_CONTACTED' AND NOT s.do_not_contact AND a.account_status = 'ACTIVE') AS "neverCalled",
                       (SELECT count(*) FROM crm.account a WHERE %s AND a.account_status = 'ACTIVE' AND a.visibility_zone = 'PROSPECTING'
                           AND coalesce(a.last_activity_at, a.created_at) < :stale) AS "staleAccounts"
                """.formatted(MY_ACCOUNTS), p);
    }

    static long workingDays(LocalDate from, LocalDate toExclusive) {
        long d = 0;
        for (LocalDate x = from; x.isBefore(toExclusive); x = x.plusDays(1))
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) d++;
        return d;
    }

    static long n(Object o) { return o instanceof Number x ? x.longValue() : 0; }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
