package com.prosperr.crm.reporting.application;

import com.prosperr.crm.shared.jdbc.AccountFilter;
import com.prosperr.crm.shared.security.Actor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** PRD reports 1–6 and M4-8 SQL quality (BRD 13). */
@Service
@Transactional(readOnly = true)
public class ReportService {
    /** Accounts whose engaged stakeholders are all L3–L5 (or unmapped). */
    static final String JUNIOR_ONLY_IDS = """
            SELECT s.account_id FROM crm.stakeholder s
             WHERE s.engagement_status IN ('CONNECTED','MEETING_SCHEDULED','MEETING_COMPLETED','FOLLOW_UP_PENDING')
             GROUP BY s.account_id HAVING bool_and(coalesce(s.level, 9) >= 3)""";
    /** Pipeline accounts with no engaged Finance/CFO or CHRO (L1–L2 in Finance or HR). */
    static final String NO_SENIOR_IDS = """
            SELECT a2.id FROM crm.account a2 WHERE a2.visibility_zone = 'PIPELINE' AND NOT EXISTS (
              SELECT 1 FROM crm.stakeholder s WHERE s.account_id = a2.id AND s.department IN ('FINANCE','HR') AND s.level <= 2
                 AND s.engagement_status IN ('CONNECTED','MEETING_SCHEDULED','MEETING_COMPLETED','FOLLOW_UP_PENDING'))""";

    private final NamedParameterJdbcTemplate jdbc;
    public ReportService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Report 1 (BRD key report): L1 done, never reached L2, with every segment the BRD lists. */
    public List<Map<String, Object>> l1NotProgressed(AccountFilter f, Actor viewer) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = f.where(viewer, p);
        return jdbc.queryForList("""
                SELECT v.account_id AS "accountId", v.name AS "account", ae.full_name AS "aeOwner", bdr.full_name AS "bdrOwner",
                       v.size_bucket_code AS "sizeBucket", a.license_potential AS "licensePotential",
                       v.spoc_designation AS "spocDesignation", v.spoc_engagement AS "spocResponsiveness",
                       v.multithreading_started AS "multithreadingStarted",
                       coalesce(sr.label, ir.label) AS "stagnationReason", v.account_status AS "status",
                       v.last_activity_at AS "lastInteraction", v.next_follow_up_at AS "nextFollowUp"
                  FROM crm.v_l1_not_progressed v JOIN crm.account a ON a.id = v.account_id
                  LEFT JOIN crm.app_user ae ON ae.id = v.ae_owner_id LEFT JOIN crm.app_user bdr ON bdr.id = v.sql_credit_bdr_id
                  LEFT JOIN crm.picklist_value sr ON sr.id = v.stagnation_reason_id LEFT JOIN crm.picklist_value ir ON ir.id = v.inactive_reason_id"""
                + where + " ORDER BY v.last_activity_at NULLS FIRST", p);
    }

    /** Report 2: only junior stakeholders engaged, and no Finance/CFO or CHRO engagement. */
    public Map<String, List<Map<String, Object>>> stakeholderCoverage(AccountFilter f, Actor viewer) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = f.where(viewer, p);
        String cols = "SELECT a.id AS \"accountId\", a.name AS \"account\", a.lifecycle_stage AS \"stage\", u.full_name AS \"aeOwner\""
                + " FROM crm.account a LEFT JOIN crm.app_user u ON u.id = a.ae_owner_id";
        return Map.of(
                "juniorOnly", jdbc.queryForList(cols + where + " AND a.id IN (" + JUNIOR_ONLY_IDS + ") ORDER BY a.name", p),
                "noFinanceOrChroEngagement", jdbc.queryForList(cols + where + " AND a.id IN (" + NO_SENIOR_IDS + ") ORDER BY a.name", p));
    }

    /** Report 3: inactive reasons over time, and reactivations by reason. */
    public Map<String, List<Map<String, Object>>> inactiveReasons(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        return Map.of(
                "movedInactiveByMonth", jdbc.queryForList("""
                        SELECT to_char(date_trunc('month', h.changed_at AT TIME ZONE 'Asia/Kolkata'), 'YYYY-MM') AS "month",
                               pv.label AS "reason", h.stage_at_change AS "stage", count(*) AS "count"
                          FROM crm.account_status_history h LEFT JOIN crm.picklist_value pv ON pv.id = h.reason_id
                         WHERE h.to_status = 'INACTIVE' AND (CAST(:from AS date) IS NULL OR h.changed_at >= CAST(:from AS date))
                           AND (CAST(:to AS date) IS NULL OR h.changed_at < CAST(:to AS date) + 1)
                         GROUP BY 1, 2, 3 ORDER BY 1, 4 DESC""", p),
                "reactivationsByReason", jdbc.queryForList("""
                        SELECT coalesce(pv.label, 'Not given') AS "reason", h.returned_to_stage AS "returnedTo", count(*) AS "count"
                          FROM crm.account_status_history h LEFT JOIN crm.picklist_value pv ON pv.id = h.reason_id
                         WHERE h.to_status = 'ACTIVE' AND (CAST(:from AS date) IS NULL OR h.changed_at >= CAST(:from AS date))
                           AND (CAST(:to AS date) IS NULL OR h.changed_at < CAST(:to AS date) + 1)
                         GROUP BY 1, 2 ORDER BY 3 DESC""", p),
                "currentInactiveByReasonAndStage", jdbc.queryForList("""
                        SELECT pv.label AS "reason", a.lifecycle_stage AS "stage", count(*) AS "count"
                          FROM crm.account a JOIN crm.picklist_value pv ON pv.id = a.inactive_reason_id
                         WHERE a.account_status = 'INACTIVE' GROUP BY 1, 2 ORDER BY 3 DESC""", Map.of()));
    }

    /** Report 4: AE workload distribution and assignment history. */
    public Map<String, List<Map<String, Object>>> workload(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        return Map.of(
                "current", jdbc.queryForList("""
                        SELECT full_name AS "ae", active_accounts AS "active", l1_pending AS "l1Pending", l2_pipeline AS "l2",
                               l3_pipeline AS "l3", negotiation, inactive_accounts AS "inactive", total_assigned AS "total",
                               weighted_load AS "weightedLoad" FROM crm.v_ae_capacity ORDER BY weighted_load DESC""", Map.of()),
                "assignments", jdbc.queryForList("""
                        SELECT u.full_name AS "owner", h.owner_type AS "type", h.method, count(*) AS "count",
                               count(*) FILTER (WHERE h.previous_owner_id IS NOT NULL) AS "reassignments",
                               count(*) FILTER (WHERE h.recommended_owner_id = h.new_owner_id) AS "followedRecommendation"
                          FROM crm.assignment_history h JOIN crm.app_user u ON u.id = h.new_owner_id
                         WHERE (CAST(:from AS date) IS NULL OR h.assigned_at >= CAST(:from AS date))
                           AND (CAST(:to AS date) IS NULL OR h.assigned_at < CAST(:to AS date) + 1)
                         GROUP BY 1, 2, 3 ORDER BY 1""", p));
    }

    /** Report 5: lead ageing by stage and follow-up compliance (tasks done on or before due). */
    public Map<String, List<Map<String, Object>>> ageing(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        return Map.of(
                "ageingByStage", jdbc.queryForList("""
                        SELECT a.lifecycle_stage AS "stage",
                               count(*) FILTER (WHERE now() - coalesce(o.stage_entered_at, a.created_at) <= interval '15 days') AS "0-15",
                               count(*) FILTER (WHERE now() - coalesce(o.stage_entered_at, a.created_at) > interval '15 days'
                                                  AND now() - coalesce(o.stage_entered_at, a.created_at) <= interval '30 days') AS "16-30",
                               count(*) FILTER (WHERE now() - coalesce(o.stage_entered_at, a.created_at) > interval '30 days'
                                                  AND now() - coalesce(o.stage_entered_at, a.created_at) <= interval '60 days') AS "31-60",
                               count(*) FILTER (WHERE now() - coalesce(o.stage_entered_at, a.created_at) > interval '60 days') AS "60+"
                          FROM crm.account a LEFT JOIN crm.opportunity o ON o.account_id = a.id AND o.is_primary
                         WHERE a.account_status = 'ACTIVE' GROUP BY 1 ORDER BY 1""", Map.of()),
                "followUpCompliance", jdbc.queryForList("""
                        SELECT u.full_name AS "user", count(*) AS "tasksDue",
                               count(*) FILTER (WHERE t.status = 'DONE' AND t.completed_at <= t.due_at + interval '1 day') AS "onTime",
                               round(100.0 * count(*) FILTER (WHERE t.status = 'DONE' AND t.completed_at <= t.due_at + interval '1 day')
                                     / nullif(count(*), 0), 1) AS "compliancePct"
                          FROM crm.task t JOIN crm.app_user u ON u.id = t.assignee_id
                         WHERE t.due_at < now() AND (CAST(:from AS date) IS NULL OR t.due_at >= CAST(:from AS date))
                           AND (CAST(:to AS date) IS NULL OR t.due_at < CAST(:to AS date) + 1)
                         GROUP BY 1 ORDER BY 4 NULLS LAST""", p));
    }

    /** M4-8: SQL quality by location priority and stakeholder level; BDR vs AE corrections. */
    public Map<String, List<Map<String, Object>>> sqlQuality(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        String range = " AND (CAST(:from AS date) IS NULL OR s.submitted_at >= CAST(:from AS date))"
                + " AND (CAST(:to AS date) IS NULL OR s.submitted_at < CAST(:to AS date) + 1)";
        return Map.of(
                "byLocationPriority", jdbc.queryForList("""
                        SELECT coalesce(s.location_priority, 'NONE') AS "locationPriority", count(*) AS "submitted",
                               count(*) FILTER (WHERE s.status = 'QUALIFIED_SQL') AS "approved",
                               count(*) FILTER (WHERE s.status = 'REJECTED_NOT_ICP') AS "rejected"
                          FROM crm.sql_submission s WHERE s.submitted_at IS NOT NULL""" + range + " GROUP BY 1 ORDER BY 1", p),
                "byStakeholderLevel", jdbc.queryForList("""
                        SELECT CASE WHEN st.level IN (1, 2) THEN 'L' || st.level
                                    WHEN st.level BETWEEN 3 AND 5 THEN 'Referred L3-L5' ELSE 'Unmapped' END AS "level",
                               count(*) AS "submitted", count(*) FILTER (WHERE s.status = 'QUALIFIED_SQL') AS "approved",
                               count(*) FILTER (WHERE s.status = 'REJECTED_NOT_ICP') AS "rejected"
                          FROM crm.sql_submission s LEFT JOIN crm.stakeholder st ON st.id = s.qualifying_stakeholder_id
                         WHERE s.submitted_at IS NOT NULL""" + range + " GROUP BY 1 ORDER BY 1", p),
                "byBdr", jdbc.queryForList("""
                        SELECT u.full_name AS "bdr", count(*) AS "submitted", count(*) FILTER (WHERE s.status = 'QUALIFIED_SQL') AS "approved",
                               count(*) FILTER (WHERE s.status = 'REJECTED_NOT_ICP') AS "rejected",
                               count(*) FILTER (WHERE s.is_small_exception AND s.status = 'QUALIFIED_SQL') AS "smallApproved",
                               (SELECT count(*) FROM crm.qualification_correction qc JOIN crm.sql_submission s2 ON s2.id = qc.sql_submission_id
                                 WHERE s2.submitted_by = s.submitted_by) AS "aeCorrections"
                          FROM crm.sql_submission s JOIN crm.app_user u ON u.id = s.submitted_by
                         WHERE s.submitted_at IS NOT NULL""" + range + " GROUP BY u.full_name, s.submitted_by ORDER BY 1", p),
                "corrections", jdbc.queryForList("""
                        SELECT a.name AS "account", qc.field_name AS "field", qc.bdr_value AS "bdrValue", qc.corrected_value AS "aeValue",
                               u.full_name AS "correctedBy", qc.corrected_at AS "correctedAt"
                          FROM crm.qualification_correction qc JOIN crm.sql_submission s ON s.id = qc.sql_submission_id
                          JOIN crm.account a ON a.id = s.account_id JOIN crm.app_user u ON u.id = qc.corrected_by
                         WHERE true""" + range + " ORDER BY qc.corrected_at DESC", p));
    }
}
