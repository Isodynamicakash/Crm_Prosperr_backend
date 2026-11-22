package com.prosperr.crm.audit.application;

import com.prosperr.crm.audit.api.AuditApi;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService implements AuditApi {
    private final NamedParameterJdbcTemplate jdbc;
    public AuditService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(String entityType, UUID entityId, Action action, String field, Object oldValue, Object newValue, UUID by) {
        jdbc.update("""
                INSERT INTO crm.audit_log (entity_type, entity_id, action, field_name, old_value, new_value, changed_by)
                VALUES (:t, :id, :a, :f, :o, :n, :by)""",
                new MapSqlParameterSource().addValue("t", entityType).addValue("id", entityId).addValue("a", action.name())
                        .addValue("f", field).addValue("o", oldValue == null ? null : oldValue.toString())
                        .addValue("n", newValue == null ? null : newValue.toString()).addValue("by", by));
    }

    public List<Map<String, Object>> history(String entityType, UUID entityId) {
        return jdbc.queryForList("""
                SELECT l.changed_at AS "changedAt", l.action, l.field_name AS "field", l.old_value AS "oldValue",
                       l.new_value AS "newValue", u.full_name AS "changedBy"
                  FROM crm.audit_log l LEFT JOIN crm.app_user u ON u.id = l.changed_by
                 WHERE l.entity_type = :t AND l.entity_id = :id ORDER BY l.changed_at DESC, l.id DESC""",
                Map.of("t", entityType, "id", entityId));
    }
}
