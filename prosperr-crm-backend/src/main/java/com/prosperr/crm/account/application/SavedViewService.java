package com.prosperr.crm.account.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** M1-6: list views with saved filters. */
@Service
public class SavedViewService {
    private final NamedParameterJdbcTemplate jdbc;
    public SavedViewService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(UUID userId, String entity) {
        return jdbc.queryForList("""
                SELECT id, name, entity, filters::text AS filters, is_shared AS "shared" FROM crm.saved_view
                 WHERE (user_id = :u OR is_shared) AND entity = :e ORDER BY name""", Map.of("u", userId, "e", entity));
    }

    @Transactional
    public UUID save(UUID userId, String name, String entity, String filtersJson, boolean shared) {
        return jdbc.queryForObject("""
                INSERT INTO crm.saved_view (user_id, name, entity, filters, is_shared) VALUES (:u, :n, :e, CAST(:f AS jsonb), :s)
                ON CONFLICT (user_id, entity, name) DO UPDATE SET filters = EXCLUDED.filters, is_shared = EXCLUDED.is_shared
                RETURNING id""",
                new MapSqlParameterSource().addValue("u", userId).addValue("n", name).addValue("e", entity)
                        .addValue("f", filtersJson).addValue("s", shared), UUID.class);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        jdbc.update("DELETE FROM crm.saved_view WHERE id = :id AND user_id = :u", Map.of("id", id, "u", userId));
    }
}
