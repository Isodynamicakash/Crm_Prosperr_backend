package com.prosperr.crm.outreach.application;

import com.prosperr.crm.outreach.domain.MergeContext;
import com.prosperr.crm.outreach.domain.TemplateRenderer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-managed templates and snippets (PRD Appendix A; "admins edit them in the CRM"). */
@Service
public class TemplateService {
    private final NamedParameterJdbcTemplate jdbc;
    public TemplateService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("SELECT id, code, name, subject, body, is_internal AS \"internal\", is_active AS \"active\", version FROM crm.email_template ORDER BY code", Map.of());
    }

    public List<Map<String, Object>> snippets() {
        return jdbc.queryForList("SELECT id, code, variant, content, is_active AS \"active\" FROM crm.email_snippet ORDER BY code, variant", Map.of());
    }

    public record TemplateUpdate(String name, String subject, String body, Boolean active) {}

    @Transactional
    public void updateTemplate(String code, TemplateUpdate u, UUID by) {
        jdbc.update("""
                UPDATE crm.email_template SET name = coalesce(:n, name), subject = coalesce(:s, subject), body = coalesce(:b, body),
                  is_active = coalesce(:a, is_active), updated_by = :by, updated_at = now(), version = version + 1 WHERE code = :c""",
                new MapSqlParameterSource().addValue("n", u.name()).addValue("s", u.subject()).addValue("b", u.body())
                        .addValue("a", u.active()).addValue("by", by).addValue("c", code));
    }

    @Transactional
    public void upsertSnippet(String code, String variant, String content) {
        jdbc.update("""
                INSERT INTO crm.email_snippet (code, variant, content) VALUES (:c, :v, :t)
                ON CONFLICT (code, variant) DO UPDATE SET content = EXCLUDED.content, updated_at = now()""",
                Map.of("c", code, "v", variant, "t", content));
    }

    public TemplateRenderer renderer() {
        Map<String, Map<String, String>> m = new HashMap<>();
        jdbc.query("SELECT code, variant, content FROM crm.email_snippet WHERE is_active", Map.of(), rs -> {
            m.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
        });
        return new TemplateRenderer(m);
    }

    public TemplateRenderer.Rendered render(UUID templateId, MergeContext ctx) {
        Map<String, Object> t = jdbc.queryForMap("SELECT subject, body FROM crm.email_template WHERE id = :id", Map.of("id", templateId));
        return renderer().render((String) t.get("subject"), (String) t.get("body"), ctx);
    }
}
