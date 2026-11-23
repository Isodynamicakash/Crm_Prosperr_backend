package com.prosperr.crm.shared.jdbc;

import com.prosperr.crm.shared.security.Actor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Shared filters for lists, dashboards and drill-downs (PRD dashboards: date range, BDR, AE, city, size bucket,
 * industry, stage, temperature, Active / Inactive). Builds a WHERE fragment over alias "a" (crm.account).
 * Visibility: leadership sees everything; others see pipeline accounts plus prospects they work.
 */
public record AccountFilter(String q, String status, List<String> stages, String zone, UUID aeId, UUID bdrId,
                            UUID cityId, String sizeBucket, UUID industryId, String temperature, Boolean flagged,
                            LocalDate from, LocalDate to, UUID workedBy) {

    /** Without the "worked by" filter (dashboards, reports). */
    public AccountFilter(String q, String status, List<String> stages, String zone, UUID aeId, UUID bdrId, UUID cityId,
                         String sizeBucket, UUID industryId, String temperature, Boolean flagged, LocalDate from, LocalDate to) {
        this(q, status, stages, zone, aeId, bdrId, cityId, sizeBucket, industryId, temperature, flagged, from, to, null);
    }

    public static AccountFilter none() { return new AccountFilter(null, "ALL", null, null, null, null, null, null, null, null, null, null, null); }

    public String where(Actor viewer, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        p.addValue("leader", viewer.isLeadership()).addValue("viewer", viewer.userId());
        w.append(" AND (:leader OR a.visibility_zone = 'PIPELINE' OR EXISTS (SELECT 1 FROM crm.account_bdr vb")
         .append(" WHERE vb.account_id = a.id AND vb.bdr_id = :viewer))");
        String st = status == null ? "ACTIVE" : status;
        if (!st.equals("ALL")) { w.append(" AND a.account_status = :status"); p.addValue("status", st); }
        if (q != null && !q.isBlank()) { w.append(" AND (a.name ILIKE :q OR a.domain ILIKE :q)"); p.addValue("q", "%" + q.trim() + "%"); }
        if (stages != null && !stages.isEmpty()) { w.append(" AND a.lifecycle_stage IN (:stages)"); p.addValue("stages", stages); }
        if (zone != null) { w.append(" AND a.visibility_zone = :zone"); p.addValue("zone", zone); }
        if (aeId != null) { w.append(" AND a.ae_owner_id = :ae"); p.addValue("ae", aeId); }
        if (bdrId != null) {
            w.append(" AND EXISTS (SELECT 1 FROM crm.account_bdr fb WHERE fb.account_id = a.id AND fb.bdr_id = :bdr)");
            p.addValue("bdr", bdrId);
        }
        if (workedBy != null) {   // accounts this person has called, emailed, noted, booked or submitted
            w.append(" AND (EXISTS (SELECT 1 FROM crm.activity wa WHERE wa.account_id = a.id AND wa.logged_by = :worked)")
             .append(" OR EXISTS (SELECT 1 FROM crm.meeting wm WHERE wm.account_id = a.id AND wm.booked_by = :worked)")
             .append(" OR EXISTS (SELECT 1 FROM crm.sql_submission ws WHERE ws.account_id = a.id AND ws.submitted_by = :worked))");
            p.addValue("worked", workedBy);
        }
        if (cityId != null) { w.append(" AND a.city_id = :city"); p.addValue("city", cityId); }
        if (sizeBucket != null) { w.append(" AND a.size_bucket_code = :bucket"); p.addValue("bucket", sizeBucket); }
        if (industryId != null) { w.append(" AND a.industry_id = :industry"); p.addValue("industry", industryId); }
        if (temperature != null) {
            w.append(" AND EXISTS (SELECT 1 FROM crm.opportunity fo WHERE fo.account_id = a.id AND fo.is_primary AND fo.temperature = :temp)");
            p.addValue("temp", temperature);
        }
        if (Boolean.TRUE.equals(flagged)) w.append(" AND a.review_flagged_at IS NOT NULL");
        if (from != null) { w.append(" AND a.created_at >= CAST(:from AS date)"); p.addValue("from", from); }
        if (to != null) { w.append(" AND a.created_at < CAST(:to AS date) + 1"); p.addValue("to", to); }
        return w.append(' ').toString(); // trailing space: callers append text blocks that start with AND
    }
}
