package com.prosperr.crm.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.LocationPriority;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.domain.SizeBucket;
import com.prosperr.crm.shared.domain.Zone;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConfigService implements ConfigApi {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConfigService(NamedParameterJdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    static final String PICK = """
            SELECT id, picklist_key, code, label, sort_order, is_active, requires_revisit_date, requires_remark
              FROM crm.picklist_value""";
    static final RowMapper<PicklistItem> PICK_ROW = (rs, i) -> new PicklistItem(Db.uuid(rs, "id"),
            rs.getString("picklist_key"), rs.getString("code"), rs.getString("label"), rs.getInt("sort_order"),
            rs.getBoolean("is_active"), rs.getBoolean("requires_revisit_date"), rs.getBoolean("requires_remark"));

    @Override
    public Optional<PicklistItem> picklistItem(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.query(PICK + " WHERE id = :id", Map.of("id", id), PICK_ROW).stream().findFirst();
    }

    @Override
    public PicklistItem picklistItem(String key, String code) {
        return jdbc.query(PICK + " WHERE picklist_key = :k AND code = :c", Map.of("k", key, "c", code), PICK_ROW)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Picklist value", key + "/" + code));
    }

    @Override
    public List<PicklistItem> picklist(String key) {
        return jdbc.query(PICK + " WHERE picklist_key = :k ORDER BY sort_order, label", Map.of("k", key), PICK_ROW);
    }

    @Override
    public Optional<CityView> city(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT id, name, region, icp_priority FROM crm.city WHERE id = :id", Map.of("id", id),
                (rs, i) -> new CityView(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("region"),
                        LocationPriority.valueOf(rs.getString("icp_priority")))).stream().findFirst();
    }

    public List<CityView> cities() {
        return jdbc.query("SELECT id, name, region, icp_priority FROM crm.city WHERE is_active ORDER BY name", Map.of(),
                (rs, i) -> new CityView(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("region"),
                        LocationPriority.valueOf(rs.getString("icp_priority"))));
    }

    @Override
    public List<BucketRow> sizeBuckets() {
        return jdbc.query("SELECT code, min_licenses, max_licenses, sort_order FROM crm.size_bucket ORDER BY sort_order",
                Map.of(), (rs, i) -> new BucketRow(SizeBucket.valueOf(rs.getString("code")), rs.getInt("min_licenses"),
                        Db.integer(rs, "max_licenses"), rs.getInt("sort_order")));
    }

    static final String DESIG = """
            SELECT id, designation, designation_normalized, level, department, priority_rank, is_right_poc,
                   right_poc_min_bucket FROM crm.designation_map""";
    static final RowMapper<DesignationRow> DESIG_ROW = (rs, i) -> new DesignationRow(Db.uuid(rs, "id"),
            rs.getString("designation"), rs.getString("designation_normalized"), rs.getInt("level"),
            Department.valueOf(rs.getString("department")), rs.getInt("priority_rank"), rs.getBoolean("is_right_poc"),
            Db.enumOf(SizeBucket.class, rs.getString("right_poc_min_bucket")));

    @Override
    public List<DesignationRow> designations() { return jdbc.query(DESIG + " ORDER BY level, designation", Map.of(), DESIG_ROW); }

    @Override
    public Optional<DesignationRow> designation(UUID id) {
        return jdbc.query(DESIG + " WHERE id = :id", Map.of("id", id), DESIG_ROW).stream().findFirst();
    }

    static final RowMapper<StageThreshold> TH_ROW = (rs, i) -> new StageThreshold(
            LifecycleStage.valueOf(rs.getString("stage")), rs.getInt("inactivity_flag_days"),
            Db.integer(rs, "stagnation_days"), rs.getInt("follow_up_interval_days"));

    @Override
    public List<StageThreshold> stageThresholds() {
        return jdbc.query("SELECT stage, inactivity_flag_days, stagnation_days, follow_up_interval_days FROM crm.stage_threshold",
                Map.of(), TH_ROW);
    }

    @Override
    public StageThreshold stageThreshold(LifecycleStage stage) {
        return stageThresholds().stream().filter(t -> t.stage() == stage).findFirst()
                .orElse(new StageThreshold(stage, 60, null, 7));
    }

    @Override
    public List<ChecklistItem> checklistItems() {
        return jdbc.query("""
                SELECT id, stage, code, label, sort_order, is_exit_step, requires_attachment
                  FROM crm.stage_checklist_item WHERE is_active ORDER BY stage, sort_order""", Map.of(),
                (rs, i) -> new ChecklistItem(Db.uuid(rs, "id"), rs.getString("stage"), rs.getString("code"),
                        rs.getString("label"), rs.getInt("sort_order"), rs.getBoolean("is_exit_step"),
                        rs.getBoolean("requires_attachment")));
    }

    @Override
    public Optional<String> json(String key) {
        return jdbc.query("SELECT value::text FROM crm.app_setting WHERE key = :k", Map.of("k", key),
                (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    private Optional<JsonNode> node(String key) {
        return json(key).map(s -> {
            try { return json.readTree(s); } catch (Exception e) { return null; }
        });
    }

    @Override public String text(String key, String fallback) {
        return node(key).filter(JsonNode::isTextual).map(JsonNode::asText).orElse(fallback);
    }
    @Override public boolean flag(String key, boolean fallback) {
        return node(key).filter(JsonNode::isBoolean).map(JsonNode::asBoolean).orElse(fallback);
    }
    @Override public int number(String key, int fallback) {
        return node(key).filter(JsonNode::isNumber).map(JsonNode::asInt).orElse(fallback);
    }
    @Override public double decimal(String key, double fallback) {
        return node(key).filter(JsonNode::isNumber).map(JsonNode::asDouble).orElse(fallback);
    }

    @Override
    public Optional<String> permissionOverride(Role role, String action, Zone zone) {
        return jdbc.query("""
                SELECT scope FROM crm.role_field_permission
                 WHERE role = :r AND entity = 'action' AND field = :a AND zone IN (:z, 'ANY')
                 ORDER BY CASE zone WHEN 'ANY' THEN 1 ELSE 0 END LIMIT 1""",
                Map.of("r", role.name(), "a", action, "z", zone.name()), (rs, i) -> rs.getString(1)).stream().findFirst();
    }
}
