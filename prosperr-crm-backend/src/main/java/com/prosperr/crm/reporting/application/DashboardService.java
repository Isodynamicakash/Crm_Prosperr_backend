package com.prosperr.crm.reporting.application;

import com.prosperr.crm.shared.domain.Ist;
import com.prosperr.crm.shared.domain.PeriodType;
import com.prosperr.crm.shared.jdbc.AccountFilter;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Actor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** PRD dashboards: BDR, AE, Leadership, Pipeline hygiene (allocation lives in AssignmentService). */
@Service
@Transactional(readOnly = true)
public class DashboardService {
    /** Outcomes where the BDR reached the person. */
    static final List<String> CONNECT_CODES = List.of("CALL_LATER", "CALL_AFTER_MONTH", "ASKED_DETAILS", "EMAIL_SENT", "REMINDED",
            "L1_BOOKED", "REFERRED", "NOT_RIGHT_STAKEHOLDER", "NOT_INTERESTED", "ALREADY_USING", "NOT_POSSIBLE", "WRONG_ICP",
            "NOT_ASSOCIATED");

    private final NamedParameterJdbcTemplate jdbc;
    private final TargetService targets;
    private final Clock clock;

    public DashboardService(NamedParameterJdbcTemplate jdbc, TargetService targets, Clock clock) {
        this.jdbc = jdbc; this.targets = targets; this.clock = clock;
    }

    /** Presets: 7, 15, 30 days, this month, this quarter (PRD dashboards). */
    public LocalDate[] range(String preset, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        if (from != null && to != null) return new LocalDate[]{from, to.plusDays(1)};
        return switch (preset == null ? "30" : preset) {
            case "7" -> new LocalDate[]{today.minusDays(6), today.plusDays(1)};
            case "15" -> new LocalDate[]{today.minusDays(14), today.plusDays(1)};
            case "month" -> new LocalDate[]{today.withDayOfMonth(1), today.plusDays(1)};
            case "quarter" -> new LocalDate[]{TargetService.periodStart(PeriodType.QUARTER, today), today.plusDays(1)};
            default -> new LocalDate[]{today.minusDays(29), today.plusDays(1)};
        };
    }

    private static MapSqlParameterSource p(UUID user, LocalDate[] r) {
        return new MapSqlParameterSource().addValue("u", user).addValue("from", r[0]).addValue("to", r[1]);
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long v = jdbc.queryForObject(sql, p, Long.class);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ Live pulse (all roles)

    /**
     * Daily activity series, call-outcome mix, current stage mix and the latest activity feed.
     * Scope: a BDR or AE sees their own work; managers and admins see the whole team ({@code user == null}).
     */
    public Map<String, Object> pulse(UUID user, boolean ae, LocalDate[] r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", user, java.sql.Types.OTHER)
                .addValue("from", r[0]).addValue("to", r[1]).addValue("connects", CONNECT_CODES).addValue("ae", ae);
        String day = "(%s AT TIME ZONE 'Asia/Kolkata')::date";
        String mine = "(CAST(:u AS uuid) IS NULL OR %s)";
        String accountScope = "(CAST(:u AS uuid) IS NULL OR (:ae AND a.ae_owner_id = :u) OR (NOT :ae AND EXISTS "
                + "(SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = a.id AND ab.bdr_id = :u)))";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r[0]);
        m.put("to", r[1].minusDays(1));
        m.put("generatedAt", Instant.now(clock));
        m.put("series", jdbc.queryForList("""
                WITH d AS (SELECT generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day')::date AS day)
                SELECT d.day,
                  (SELECT count(*) FROM crm.activity act WHERE act.type = 'CALL' AND %1$s = d.day AND %2$s) AS "calls",
                  (SELECT count(*) FROM crm.activity act JOIN crm.picklist_value pv ON pv.id = act.call_outcome_id
                     WHERE act.type = 'CALL' AND pv.code IN (:connects) AND %1$s = d.day AND %2$s) AS "connects",
                  (SELECT count(*) FROM crm.sql_submission s WHERE %3$s = d.day AND %4$s) AS "sqls",
                  (SELECT count(*) FROM crm.meeting mt WHERE mt.rescheduled_from_id IS NULL AND %5$s = d.day AND %6$s) AS "meetingsBooked",
                  (SELECT count(*) FROM crm.meeting mt WHERE mt.status = 'COMPLETED' AND %7$s = d.day AND %6$s) AS "meetingsDone",
                  (SELECT count(*) FROM crm.stage_history sh JOIN crm.account a ON a.id = sh.account_id
                     WHERE sh.to_stage = 'WON' AND %8$s = d.day AND %9$s) AS "wins"
                  FROM d ORDER BY d.day""".formatted(
                        day.formatted("act.occurred_at"), mine.formatted("act.logged_by = :u"),
                        day.formatted("s.submitted_at"), mine.formatted("s.submitted_by = :u"),
                        day.formatted("mt.booked_on"), mine.formatted("(mt.booked_by = :u OR mt.ae_id = :u)"),
                        day.formatted("mt.outcome_recorded_at"), day.formatted("sh.changed_at"), accountScope), p));
        m.put("outcomeMix", jdbc.queryForList("""
                SELECT pv.label AS "outcome", pv.code, count(*) AS "count", (pv.code IN (:connects)) AS "connect"
                  FROM crm.activity act JOIN crm.picklist_value pv ON pv.id = act.call_outcome_id
                 WHERE act.type = 'CALL' AND %s >= :from AND %s < :to AND %s
                 GROUP BY pv.label, pv.code ORDER BY count(*) DESC""".formatted(
                        day.formatted("act.occurred_at"), day.formatted("act.occurred_at"), mine.formatted("act.logged_by = :u")), p));
        m.put("stageMix", jdbc.queryForList("""
                SELECT a.lifecycle_stage AS "stage", count(*) AS "count",
                       coalesce(sum(o.expected_mrr), 0) AS "mrr"
                  FROM crm.account a LEFT JOIN crm.opportunity o ON o.account_id = a.id AND o.closed_at IS NULL
                 WHERE a.account_status = 'ACTIVE' AND """ + accountScope + """
                 GROUP BY a.lifecycle_stage""", p));
        m.put("feed", jdbc.queryForList("""
                SELECT act.id, act.type, act.remarks, act.occurred_at AS "occurredAt", u.full_name AS "by",
                       a.id AS "accountId", a.name AS "accountName",
                       s.first_name || coalesce(' ' || s.last_name, '') AS "stakeholder", pv.label AS "outcome"
                  FROM crm.activity act JOIN crm.account a ON a.id = act.account_id
                  LEFT JOIN crm.app_user u ON u.id = act.logged_by
                  LEFT JOIN crm.stakeholder s ON s.id = act.stakeholder_id
                  LEFT JOIN crm.picklist_value pv ON pv.id = act.call_outcome_id
                 WHERE (CAST(:u AS uuid) IS NULL OR act.logged_by = :u OR (:ae AND a.ae_owner_id = :u))
                 ORDER BY act.occurred_at DESC LIMIT 20""", p));
        return m;
    }

    // ------------------------------------------------------------------ BDR (BRD 10.2)

    public Map<String, Object> bdr(UUID bdrId, LocalDate[] r) {
        MapSqlParameterSource p = p(bdrId, r).addValue("connects", CONNECT_CODES);
        String inRange = " AND (%s AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (%s AT TIME ZONE 'Asia/Kolkata')::date < :to";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r[0]);
        m.put("to", r[1].minusDays(1));
        m.put("leadsGenerated", count("SELECT count(*) FROM crm.stakeholder s WHERE s.created_by = :u" + inRange.formatted("s.created_at", "s.created_at"), p));
        m.put("calls", count("SELECT count(*) FROM crm.activity act WHERE act.type = 'CALL' AND act.logged_by = :u" + inRange.formatted("act.occurred_at", "act.occurred_at"), p));
        m.put("connects", count("""
                SELECT count(*) FROM crm.activity act JOIN crm.picklist_value pv ON pv.id = act.call_outcome_id
                 WHERE act.type = 'CALL' AND act.logged_by = :u AND pv.code IN (:connects)""" + inRange.formatted("act.occurred_at", "act.occurred_at"), p));
        m.put("sequencesStarted", count("SELECT count(*) FROM crm.sequence_enrollment e WHERE e.enrolled_by = :u" + inRange.formatted("e.started_at", "e.started_at"), p));
        m.put("sqlsSubmitted", count("SELECT count(*) FROM crm.sql_submission s WHERE s.submitted_by = :u" + inRange.formatted("s.submitted_at", "s.submitted_at"), p));
        m.put("sqlsApproved", count("SELECT count(*) FROM crm.sql_submission s WHERE s.submitted_by = :u AND s.status = 'QUALIFIED_SQL'" + inRange.formatted("s.validated_at", "s.validated_at"), p));
        m.put("sqlsRejected", count("SELECT count(*) FROM crm.sql_submission s WHERE s.submitted_by = :u AND s.status = 'REJECTED_NOT_ICP'" + inRange.formatted("s.validated_at", "s.validated_at"), p));
        m.put("l1Booked", count("SELECT count(*) FROM crm.meeting mt WHERE mt.level = 'L1' AND mt.booked_by = :u AND mt.rescheduled_from_id IS NULL" + inRange.formatted("mt.booked_on", "mt.booked_on"), p));
        m.put("l1Done", count("SELECT count(*) FROM crm.meeting mt WHERE mt.level = 'L1' AND mt.booked_by = :u AND mt.status = 'COMPLETED'" + inRange.formatted("mt.scheduled_start", "mt.scheduled_start"), p));
        m.put("noShows", count("SELECT count(*) FROM crm.meeting mt WHERE mt.level = 'L1' AND mt.booked_by = :u AND mt.status = 'NO_SHOW'" + inRange.formatted("mt.scheduled_start", "mt.scheduled_start"), p));
        long approved = (long) m.get("sqlsApproved");
        long small = count("SELECT count(*) FROM crm.sql_submission s WHERE s.submitted_by = :u AND s.status = 'QUALIFIED_SQL' AND s.is_small_exception" + inRange.formatted("s.validated_at", "s.validated_at"), p);
        m.put("smallAccountShare", approved == 0 ? 0.0 : Math.round(1000.0 * small / approved) / 10.0);
        long submitted = (long) m.get("sqlsSubmitted");
        m.put("sqlToL1DonePct", submitted == 0 ? null : Math.round(1000.0 * (long) m.get("l1Done") / submitted) / 10.0);
        m.put("revivalAccountsDue", count("""
                SELECT count(*) FROM crm.v_revival_queue v WHERE EXISTS (SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = v.account_id AND ab.bdr_id = :u)""", p));
        m.put("targets", targets.achievement(bdrId, PeriodType.MONTH, r[1].minusDays(1)));
        return m;
    }

    // ------------------------------------------------------------------ AE (BRD 10.3)

    public Map<String, Object> ae(UUID aeId, LocalDate[] r) {
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        MapSqlParameterSource p = p(aeId, r)
                .addValue("start", Db.ts(today.atStartOfDay(Ist.ZONE).toInstant()))
                .addValue("end", Db.ts(today.plusDays(1).atStartOfDay(Ist.ZONE).toInstant()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assigned", count("SELECT count(*) FROM crm.account a WHERE a.ae_owner_id = :u", p));
        m.put("active", count("SELECT count(*) FROM crm.account a WHERE a.ae_owner_id = :u AND a.account_status = 'ACTIVE'", p));
        m.put("inactive", count("SELECT count(*) FROM crm.account a WHERE a.ae_owner_id = :u AND a.account_status = 'INACTIVE'", p));
        m.put("l1Pending", count("""
                SELECT count(DISTINCT mt.account_id) FROM crm.meeting mt WHERE mt.ae_id = :u AND mt.level = 'L1'
                   AND mt.status IN ('SCHEDULED','RESCHEDULED') AND mt.outcome IS NULL""", p));
        m.put("l1Completed", count("SELECT count(*) FROM crm.meeting mt WHERE mt.ae_id = :u AND mt.level = 'L1' AND mt.status = 'COMPLETED'", p));
        m.put("l2Pending", count("""
                SELECT count(*) FROM crm.account a WHERE a.ae_owner_id = :u AND a.account_status = 'ACTIVE'
                   AND a.lifecycle_stage IN ('L1','MULTITHREADING')""", p));
        m.put("l3Pending", count("SELECT count(*) FROM crm.account a WHERE a.ae_owner_id = :u AND a.account_status = 'ACTIVE' AND a.lifecycle_stage = 'L2'", p));
        m.put("followUpsDueToday", count("SELECT count(*) FROM crm.task t WHERE t.assignee_id = :u AND t.status = 'OPEN' AND t.due_at >= :start AND t.due_at < :end", p));
        m.put("followUpsOverdue", count("SELECT count(*) FROM crm.task t WHERE t.assignee_id = :u AND t.status = 'OPEN' AND t.due_at < :start", p));
        m.put("unresponsiveStakeholders", count("""
                SELECT count(*) FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id
                 WHERE a.ae_owner_id = :u AND a.account_status = 'ACTIVE' AND s.engagement_status = 'UNRESPONSIVE'""", p));
        m.put("accountsNeedingMultithreading", count(NEEDS_MT + " AND a.ae_owner_id = :u", p));
        m.put("toBeMoved", count("SELECT count(*) FROM crm.account a WHERE a.ae_owner_id = :u AND a.review_flagged_at IS NOT NULL AND a.account_status = 'ACTIVE'", p));
        m.put("meetingOutcomesOverdue", count("""
                SELECT count(*) FROM crm.meeting mt WHERE mt.ae_id = :u AND mt.outcome IS NULL AND mt.status IN ('SCHEDULED','RESCHEDULED')
                   AND mt.scheduled_start < now()""", p));
        m.put("opportunitiesByStage", jdbc.queryForList("""
                SELECT a.lifecycle_stage AS "stage", count(*) AS "count" FROM crm.account a
                 WHERE a.ae_owner_id = :u AND a.account_status = 'ACTIVE' GROUP BY 1 ORDER BY 1""", p));
        m.put("mrrByTemperature", jdbc.queryForList("""
                SELECT coalesce(o.temperature, 'UNSET') AS "temperature", count(*) AS "count", coalesce(sum(o.expected_mrr), 0) AS "mrr"
                  FROM crm.opportunity o JOIN crm.account a ON a.id = o.account_id
                 WHERE a.ae_owner_id = :u AND a.account_status = 'ACTIVE' AND o.stage NOT IN ('WON','LOST','IMPLEMENTATION')
                 GROUP BY 1 ORDER BY 1""", p));
        m.put("targets", targets.achievement(aeId, PeriodType.MONTH, r[1].minusDays(1)));
        return m;
    }

    /** Active pipeline accounts with one stakeholder or fewer engaged, or a silent primary SPOC. */
    static final String NEEDS_MT = """
            SELECT count(*) FROM crm.account a
             WHERE a.account_status = 'ACTIVE' AND a.visibility_zone = 'PIPELINE'
               AND a.lifecycle_stage NOT IN ('WON','LOST','IMPLEMENTATION')
               AND ((SELECT count(*) FROM crm.stakeholder s WHERE s.account_id = a.id
                       AND s.engagement_status IN ('CONNECTED','MEETING_SCHEDULED','MEETING_COMPLETED','FOLLOW_UP_PENDING')) <= 1
                    OR EXISTS (SELECT 1 FROM crm.stakeholder s WHERE s.account_id = a.id AND s.is_primary_spoc
                       AND s.engagement_status = 'UNRESPONSIVE'))""";

    // ------------------------------------------------------------------ Leadership (BRD 10.1)

    public Map<String, Object> leadership(AccountFilter f, Actor viewer, LocalDate[] r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", r[0]).addValue("to", r[1]);
        String where = f.where(viewer, p);
        String scoped = "SELECT a.id FROM crm.account a" + where;
        Map<String, Object> m = new LinkedHashMap<>();
        long leads = count("SELECT count(*) FROM crm.account a" + where
                + " AND (a.created_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (a.created_at AT TIME ZONE 'Asia/Kolkata')::date < :to", p);
        long sql = count("""
                SELECT count(DISTINCT s.account_id) FROM crm.sql_submission s WHERE s.account_id IN (""" + scoped + """
                ) AND (s.submitted_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (s.submitted_at AT TIME ZONE 'Asia/Kolkata')::date < :to""", p);
        Map<String, Long> reached = new LinkedHashMap<>();
        for (String st : List.of("L1", "L2", "L3", "WON")) {
            reached.put(st, count("""
                    SELECT count(DISTINCT h.account_id) FROM crm.stage_history h WHERE h.to_stage = '""" + st + "' AND h.account_id IN (" + scoped + """
                    ) AND (h.changed_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (h.changed_at AT TIME ZONE 'Asia/Kolkata')::date < :to""", p));
        }
        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(step("Leads", leads, null));
        funnel.add(step("SQL", sql, leads));
        funnel.add(step("L1", reached.get("L1"), sql));
        funnel.add(step("L2", reached.get("L2"), reached.get("L1")));
        funnel.add(step("L3", reached.get("L3"), reached.get("L2")));
        funnel.add(step("Won", reached.get("WON"), reached.get("L3")));
        m.put("funnel", funnel);
        m.put("averageDaysInStage", jdbc.queryForList("""
                WITH h AS (
                  SELECT h.opportunity_id, h.to_stage, h.changed_at,
                         lead(h.changed_at) OVER (PARTITION BY h.opportunity_id ORDER BY h.changed_at, h.id) AS left_at
                    FROM crm.stage_history h WHERE h.account_id IN (""" + scoped + """
                ))
                SELECT to_stage AS "stage", round(avg(extract(epoch FROM (coalesce(left_at, now()) - changed_at)) / 86400)::numeric, 1) AS "avgDays",
                       count(*) AS "opportunities"
                  FROM h GROUP BY to_stage ORDER BY min(changed_at)""", p));
        m.put("stuckAccounts", jdbc.queryForList("""
                SELECT a.lifecycle_stage AS "stage", count(*) AS "count" FROM crm.account a
                  JOIN crm.opportunity o ON o.account_id = a.id AND o.is_primary
                  JOIN crm.stage_threshold t ON t.stage = a.lifecycle_stage""" + where + """
                   AND a.account_status = 'ACTIVE' AND o.stage_entered_at < now() - make_interval(days => coalesce(t.stagnation_days, 30))
                 GROUP BY 1 ORDER BY 1""", p));
        m.put("winsAndLosses", jdbc.queryForList("""
                SELECT o.stage AS "outcome", count(*) AS "count", coalesce(sum(o.expected_mrr), 0) AS "mrr" FROM crm.opportunity o
                 WHERE o.stage IN ('WON','LOST','IMPLEMENTATION') AND o.account_id IN (""" + scoped + """
                ) AND (o.closed_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (o.closed_at AT TIME ZONE 'Asia/Kolkata')::date < :to
                 GROUP BY 1""", p));
        MapSqlParameterSource pAll = new MapSqlParameterSource();
        AccountFilter all = new AccountFilter(f.q(), "ALL", f.stages(), f.zone(), f.aeId(), f.bdrId(), f.cityId(), f.sizeBucket(),
                f.industryId(), f.temperature(), f.flagged(), null, null);
        m.put("activeVsInactive", jdbc.queryForList("SELECT a.account_status AS \"status\", count(*) AS \"count\" FROM crm.account a"
                + all.where(viewer, pAll) + " AND a.visibility_zone = 'PIPELINE' GROUP BY 1", pAll));
        m.put("pipelineMrr", jdbc.queryForObject("""
                SELECT coalesce(sum(o.expected_mrr), 0) FROM crm.opportunity o WHERE o.stage NOT IN ('WON','LOST','IMPLEMENTATION')
                   AND o.account_id IN (""" + scoped + ")", p, java.math.BigDecimal.class));
        m.put("opportunitiesByAe", jdbc.queryForList("""
                SELECT coalesce(u.full_name, 'Unassigned') AS "ae", a.lifecycle_stage AS "stage", count(*) AS "count"
                  FROM crm.account a LEFT JOIN crm.app_user u ON u.id = a.ae_owner_id""" + where + """
                   AND a.visibility_zone = 'PIPELINE' GROUP BY 1, 2 ORDER BY 1, 2""", p));
        m.put("opportunitiesByBdr", jdbc.queryForList("""
                SELECT coalesce(u.full_name, 'None') AS "bdr", a.lifecycle_stage AS "stage", count(*) AS "count"
                  FROM crm.account a LEFT JOIN crm.app_user u ON u.id = a.sql_credit_bdr_id""" + where + """
                   AND a.visibility_zone = 'PIPELINE' GROUP BY 1, 2 ORDER BY 1, 2""", p));
        return m;
    }

    private static Map<String, Object> step(String name, long n, Long previous) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("stage", name);
        s.put("count", n);
        s.put("conversionFromPreviousPct", previous == null || previous == 0 ? null : Math.round(1000.0 * n / previous) / 10.0);
        return s;
    }

    // ------------------------------------------------------------------ Pipeline hygiene

    public Map<String, Object> hygiene() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("noNextStep", jdbc.queryForList("""
                SELECT a.id, a.name, a.lifecycle_stage AS "stage", u.full_name AS "aeOwner", a.next_step_due AS "nextStepDue"
                  FROM crm.account a LEFT JOIN crm.app_user u ON u.id = a.ae_owner_id
                 WHERE a.account_status = 'ACTIVE' AND a.visibility_zone = 'PIPELINE'
                   AND a.lifecycle_stage NOT IN ('WON','LOST','IMPLEMENTATION')
                   AND (a.next_step IS NULL OR a.next_step_due IS NULL OR a.next_step_due < current_date)
                 ORDER BY a.name""", Map.of()));
        m.put("overdueTasksByUser", jdbc.queryForList("""
                SELECT u.full_name AS "user", count(*) AS "overdue" FROM crm.task t JOIN crm.app_user u ON u.id = t.assignee_id
                 WHERE t.status = 'OPEN' AND t.due_at < now() GROUP BY 1 ORDER BY 2 DESC""", Map.of()));
        m.put("meetingsWithoutOutcome", jdbc.queryForList("""
                SELECT m.id, a.name AS "account", m.level, m.scheduled_start AS "scheduledStart", u.full_name AS "ae"
                  FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id LEFT JOIN crm.app_user u ON u.id = m.ae_id
                 WHERE m.outcome IS NULL AND m.status IN ('SCHEDULED','RESCHEDULED') AND m.scheduled_start < now() ORDER BY m.scheduled_start""", Map.of()));
        m.put("inactiveMissingReasonDetail", jdbc.queryForList("""
                SELECT a.id, a.name, pv.label AS "reason" FROM crm.account a JOIN crm.picklist_value pv ON pv.id = a.inactive_reason_id
                 WHERE a.account_status = 'INACTIVE'
                   AND ((pv.requires_revisit_date AND a.revisit_on IS NULL) OR (pv.requires_remark AND coalesce(a.inactive_remark, '') = ''))""", Map.of()));
        m.put("stagnationReasonMissing", jdbc.queryForList("""
                SELECT a.id, a.name, a.size_bucket_code AS "sizeBucket", o.stage_entered_at AS "atL1Since", u.full_name AS "aeOwner"
                  FROM crm.account a JOIN crm.opportunity o ON o.account_id = a.id AND o.is_primary
                  JOIN crm.stage_threshold t ON t.stage = 'L1' LEFT JOIN crm.app_user u ON u.id = a.ae_owner_id
                 WHERE a.account_status = 'ACTIVE' AND o.stage = 'L1' AND a.size_bucket_code IN ('LARGE','ENTERPRISE')
                   AND o.stagnation_reason_id IS NULL AND t.stagnation_days IS NOT NULL
                   AND o.stage_entered_at < now() - make_interval(days => t.stagnation_days)""", Map.of()));
        return m;
    }

    // ------------------------------------------------------------------ drill-down: every number opens its accounts

    public List<Map<String, Object>> drilldown(String metric, AccountFilter f, Actor viewer) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = f.where(viewer, p);
        String extra = switch (metric) {
            case "activePipeline" -> " AND a.account_status = 'ACTIVE' AND a.visibility_zone = 'PIPELINE'";
            case "inactivePipeline" -> " AND a.account_status = 'INACTIVE' AND a.visibility_zone = 'PIPELINE'";
            case "toBeMoved" -> " AND a.review_flagged_at IS NOT NULL AND a.account_status = 'ACTIVE'";
            case "noNextStep" -> " AND a.account_status = 'ACTIVE' AND a.visibility_zone = 'PIPELINE' AND (a.next_step IS NULL OR a.next_step_due < current_date)";
            case "needsMultithreading" -> " AND a.id IN (" + NEEDS_MT.replace("SELECT count(*) FROM crm.account a", "SELECT a.id FROM crm.account a") + ")";
            case "juniorOnly" -> " AND a.id IN (" + ReportService.JUNIOR_ONLY_IDS + ")";
            case "noSeniorFinanceOrHr" -> " AND a.id IN (" + ReportService.NO_SENIOR_IDS + ")";
            case "revival" -> " AND a.id IN (SELECT account_id FROM crm.v_revival_queue)";
            default -> {
                if (metric.startsWith("stage:")) {
                    p.addValue("drillStage", metric.substring(6));
                    yield " AND a.lifecycle_stage = :drillStage";
                }
                throw new IllegalArgumentException("Unknown metric " + metric);
            }
        };
        return jdbc.queryForList(com.prosperr.crm.shared.jdbc.AccountListSql.COLUMNS + where + extra
                + " ORDER BY a.name LIMIT 2000", p);
    }

    Instant now() { return Instant.now(clock); }
}
