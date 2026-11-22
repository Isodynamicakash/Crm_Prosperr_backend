package com.prosperr.crm.config.application;

import com.prosperr.crm.config.api.DesignationMapped;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.SizeBucket;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin edits of configuration tables (PRD M10-1). */
@Service
public class ConfigAdminService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public ConfigAdminService(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.events = events;
    }

    public record PicklistUpsert(String key, String code, String label, Integer sortOrder, Boolean active,
                                 Boolean requiresRevisitDate, Boolean requiresRemark) {}

    @Transactional
    public void upsertPicklist(PicklistUpsert p) {
        jdbc.update("""
                INSERT INTO crm.picklist_value (picklist_key, code, label, sort_order, is_active, requires_revisit_date, requires_remark)
                VALUES (:k, :c, :l, coalesce(:s, 0), coalesce(:a, true), coalesce(:rv, false), coalesce(:rr, false))
                ON CONFLICT (picklist_key, code) DO UPDATE SET
                  label = EXCLUDED.label, sort_order = EXCLUDED.sort_order, is_active = EXCLUDED.is_active,
                  requires_revisit_date = EXCLUDED.requires_revisit_date, requires_remark = EXCLUDED.requires_remark""",
                new MapSqlParameterSource().addValue("k", p.key()).addValue("c", p.code()).addValue("l", p.label())
                        .addValue("s", p.sortOrder()).addValue("a", p.active()).addValue("rv", p.requiresRevisitDate())
                        .addValue("rr", p.requiresRemark()));
    }

    @Transactional
    public void setSetting(String key, String jsonValue, UUID by) {
        jdbc.update("""
                INSERT INTO crm.app_setting (key, value, updated_by, updated_at) VALUES (:k, CAST(:v AS jsonb), :by, now())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()""",
                new MapSqlParameterSource().addValue("k", key).addValue("v", jsonValue).addValue("by", by));
    }

    public List<Map<String, Object>> settings() {
        return jdbc.queryForList("SELECT key, value::text AS value, description FROM crm.app_setting ORDER BY key", Map.of());
    }

    public record CityUpsert(String name, String region, String icpPriority, Boolean active) {}

    @Transactional
    public void upsertCity(CityUpsert c) {
        jdbc.update("""
                INSERT INTO crm.city (name, region, icp_priority, is_active) VALUES (:n, :r, :p, coalesce(:a, true))
                ON CONFLICT (name) DO UPDATE SET region = EXCLUDED.region, icp_priority = EXCLUDED.icp_priority,
                  is_active = EXCLUDED.is_active""",
                new MapSqlParameterSource().addValue("n", c.name()).addValue("r", c.region())
                        .addValue("p", c.icpPriority()).addValue("a", c.active()));
    }

    public record BucketBounds(SizeBucket bucket, int min, Integer max) {}

    /** Change bucket bounds and recompute every account's bucket (M1-4). Returns accounts updated. */
    @Transactional
    public int updateBuckets(List<BucketBounds> bounds) {
        for (BucketBounds b : bounds) {
            if (b.max() != null && b.max() < b.min()) throw new DomainException("BUCKET_RANGE", "max must be >= min");
            jdbc.update("UPDATE crm.size_bucket SET min_licenses = :min, max_licenses = :max WHERE code = :c",
                    new MapSqlParameterSource().addValue("min", b.min()).addValue("max", b.max()).addValue("c", b.bucket().name()));
        }
        Integer n = jdbc.queryForObject("SELECT crm.recompute_size_buckets()", Map.of(), Integer.class);
        return n == null ? 0 : n;
    }

    public record DesignationUpsert(String designation, int level, Department department, int priorityRank,
                                    boolean rightPoc, SizeBucket rightPocMinBucket) {}

    /** Add or change a title; stakeholders already holding it pick up the level (review list, M1-5). */
    @Transactional
    public void upsertDesignation(DesignationUpsert d) {
        if (d.level() < 1 || d.level() > 5) throw new DomainException("LEVEL_RANGE", "Level must be 1..5");
        jdbc.update("""
                INSERT INTO crm.designation_map (designation, level, department, priority_rank, is_right_poc, right_poc_min_bucket)
                VALUES (:d, :l, :dep, :p, :rp, :mb)
                ON CONFLICT (designation_normalized) DO UPDATE SET level = EXCLUDED.level, department = EXCLUDED.department,
                  priority_rank = EXCLUDED.priority_rank, is_right_poc = EXCLUDED.is_right_poc,
                  right_poc_min_bucket = EXCLUDED.right_poc_min_bucket""",
                new MapSqlParameterSource().addValue("d", d.designation()).addValue("l", d.level())
                        .addValue("dep", d.department().name()).addValue("p", d.priorityRank()).addValue("rp", d.rightPoc())
                        .addValue("mb", d.rightPocMinBucket() == null ? null : d.rightPocMinBucket().name()));
        String normalized = jdbc.queryForObject("SELECT lower(regexp_replace(btrim(:d), '\\s+', ' ', 'g'))",
                Map.of("d", d.designation()), String.class);
        events.publishEvent(new DesignationMapped(normalized));
    }

    public List<Map<String, Object>> unmappedDesignations() {
        return jdbc.queryForList("""
                SELECT designation_normalized AS "designation", example_title AS "exampleTitle", stakeholders
                  FROM crm.v_unmapped_designations ORDER BY stakeholders DESC""", Map.of());
    }

    public List<Map<String, Object>> icpRules() {
        return jdbc.queryForList("SELECT id, code, name, description, config::text AS config, is_active AS active FROM crm.icp_rule ORDER BY code", Map.of());
    }

    @Transactional
    public void updateIcpRule(String code, String configJson, Boolean active, UUID by) {
        int n = jdbc.update("""
                UPDATE crm.icp_rule SET config = coalesce(CAST(:c AS jsonb), config), is_active = coalesce(:a, is_active),
                       updated_by = :by, updated_at = now() WHERE code = :code""",
                new MapSqlParameterSource().addValue("c", configJson).addValue("a", active).addValue("by", by).addValue("code", code));
        if (n == 0) throw new DomainException("UNKNOWN_RULE", "No ICP rule " + code);
    }

    public List<Map<String, Object>> callOutcomeRules() {
        return jdbc.queryForList("""
                SELECT p.code, p.label, r.sets_engagement_status AS "setsStatus", r.next_task_type AS "nextTaskType",
                       r.next_task_offset_wdays AS "offsetWorkingDays", r.requires_date AS "requiresDate",
                       r.requires_reason AS "requiresReason", r.max_attempts AS "maxAttempts",
                       r.after_max_status AS "afterMaxStatus", r.revisit_days AS "revisitDays",
                       r.offer_sequence AS "offerSequence"
                  FROM crm.call_outcome_rule r JOIN crm.picklist_value p ON p.id = r.outcome_id ORDER BY p.sort_order""", Map.of());
    }

    public record OutcomeRuleUpdate(String setsStatus, String nextTaskType, Integer offsetWorkingDays, Boolean requiresDate,
                                    Boolean requiresReason, Integer maxAttempts, String afterMaxStatus, Integer revisitDays,
                                    Boolean offerSequence) {}

    @Transactional
    public void updateCallOutcomeRule(String code, OutcomeRuleUpdate u) {
        jdbc.update("""
                UPDATE crm.call_outcome_rule r SET
                  sets_engagement_status = :s, next_task_type = :t, next_task_offset_wdays = :o,
                  requires_date = coalesce(:rd, r.requires_date), requires_reason = coalesce(:rr, r.requires_reason),
                  max_attempts = :m, after_max_status = :am, revisit_days = :rv, offer_sequence = coalesce(:os, r.offer_sequence)
                 FROM crm.picklist_value p WHERE p.id = r.outcome_id AND p.picklist_key = 'CALL_OUTCOME' AND p.code = :code""",
                new MapSqlParameterSource().addValue("s", u.setsStatus()).addValue("t", u.nextTaskType())
                        .addValue("o", u.offsetWorkingDays()).addValue("rd", u.requiresDate()).addValue("rr", u.requiresReason())
                        .addValue("m", u.maxAttempts()).addValue("am", u.afterMaxStatus()).addValue("rv", u.revisitDays())
                        .addValue("os", u.offerSequence()).addValue("code", code));
    }

    @Transactional
    public void updateStageThreshold(String stage, Integer inactivityDays, Integer stagnationDays, Integer followUpDays) {
        jdbc.update("""
                UPDATE crm.stage_threshold SET inactivity_flag_days = coalesce(:i, inactivity_flag_days),
                  stagnation_days = :s, follow_up_interval_days = coalesce(:f, follow_up_interval_days) WHERE stage = :st""",
                new MapSqlParameterSource().addValue("i", inactivityDays).addValue("s", stagnationDays)
                        .addValue("f", followUpDays).addValue("st", stage));
    }

    public record ChecklistUpsert(String stage, String code, String label, Integer sortOrder, Boolean exitStep,
                                  Boolean requiresAttachment, Boolean active) {}

    @Transactional
    public void upsertChecklistItem(ChecklistUpsert c) {
        jdbc.update("""
                INSERT INTO crm.stage_checklist_item (stage, code, label, sort_order, is_exit_step, requires_attachment, is_active)
                VALUES (:s, :c, :l, coalesce(:o, 0), coalesce(:e, false), coalesce(:ra, false), coalesce(:a, true))
                ON CONFLICT (stage, code) DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order,
                  is_exit_step = EXCLUDED.is_exit_step, requires_attachment = EXCLUDED.requires_attachment,
                  is_active = EXCLUDED.is_active""",
                new MapSqlParameterSource().addValue("s", c.stage()).addValue("c", c.code()).addValue("l", c.label())
                        .addValue("o", c.sortOrder()).addValue("e", c.exitStep()).addValue("ra", c.requiresAttachment())
                        .addValue("a", c.active()));
    }

    public record PermissionOverride(String role, String action, String zone, String scope) {}

    @Transactional
    public void setPermission(PermissionOverride p, UUID by) {
        jdbc.update("""
                INSERT INTO crm.role_field_permission (role, entity, field, zone, scope, can_view, can_edit, updated_by)
                VALUES (:r, 'action', :a, :z, :s, true, :s <> 'NONE', :by)
                ON CONFLICT (role, entity, field, zone) DO UPDATE SET scope = EXCLUDED.scope, can_edit = EXCLUDED.can_edit,
                  updated_by = EXCLUDED.updated_by, updated_at = now()""",
                new MapSqlParameterSource().addValue("r", p.role()).addValue("a", p.action()).addValue("z", p.zone())
                        .addValue("s", p.scope()).addValue("by", by));
    }

    public List<Map<String, Object>> permissions() {
        return jdbc.queryForList("SELECT role, field AS action, zone, scope FROM crm.role_field_permission WHERE entity = 'action' ORDER BY role, field", Map.of());
    }
}
