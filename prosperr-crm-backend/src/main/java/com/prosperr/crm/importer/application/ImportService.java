package com.prosperr.crm.importer.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.StakeholderApi;
import com.prosperr.crm.account.domain.Normalizer;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.prospecting.api.ActivityApi;
import com.prosperr.crm.shared.domain.ActivityType;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * PRD M1-1 and Migration: CSV/XLSX import with column mapping and a duplicate report.
 * Flow (template): upload -> parse -> map -> dry-run duplicate check (preview) -> commit row by row.
 */
@Service
public class ImportService {
    /** Target fields a column can map to. */
    public static final Set<String> FIELDS = Set.of("company", "website", "city", "employeeCount", "taxableEmployeeCount",
            "licensePotential", "industry", "leadSource", "apolloId", "firstName", "lastName", "fullName", "designation",
            "department", "email", "email2", "phone", "phone2", "linkedin", "bdrEmail", "remarks");

    private final NamedParameterJdbcTemplate jdbc;
    private final List<RowReader> readers;
    private final AccountApi accounts;
    private final StakeholderApi stakeholders;
    private final ActivityApi activities;
    private final UserDirectory users;
    private final AuditApi audit;
    private final ObjectMapper json;
    private final TransactionTemplate rowTx;

    public ImportService(NamedParameterJdbcTemplate jdbc, List<RowReader> readers, AccountApi accounts, StakeholderApi stakeholders,
                         ActivityApi activities, UserDirectory users, AuditApi audit, ObjectMapper json, PlatformTransactionManager txm) {
        this.jdbc = jdbc; this.readers = readers; this.accounts = accounts; this.stakeholders = stakeholders;
        this.activities = activities; this.users = users; this.audit = audit; this.json = json;
        this.rowTx = new TransactionTemplate(txm);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Map<String, Object> upload(String fileName, InputStream in, String source, Map<String, String> mapping, Actor actor)
            throws IOException {
        if (actor.role() == Role.AE) throw new DomainException("FORBIDDEN_IMPORT", "AEs cannot import prospects");
        for (String f : mapping.values()) if (!FIELDS.contains(f)) throw new DomainException("UNKNOWN_FIELD", "Unknown field " + f);
        RowReader reader = readers.stream().filter(r -> r.supports(fileName)).findFirst()
                .orElseThrow(() -> new DomainException("FILE_TYPE", "Upload a .csv or .xlsx file"));
        List<Map<String, String>> raw = reader.read(in);
        UUID job = jdbc.queryForObject("""
                INSERT INTO crm.import_job (source, file_name, column_mapping, status, total_rows, created_by)
                VALUES (:s, :f, CAST(:m AS jsonb), 'VALIDATING', :n, :by) RETURNING id""",
                new MapSqlParameterSource().addValue("s", source == null ? "MANUAL_UPLOAD" : source).addValue("f", fileName)
                        .addValue("m", json.writeValueAsString(mapping)).addValue("n", raw.size()).addValue("by", actor.userId()), UUID.class);
        int i = 1;
        for (Map<String, String> r : raw) {
            Map<String, String> mapped = map(r, mapping);
            Preview pv = preview(mapped);
            jdbc.update("""
                    INSERT INTO crm.import_row (import_job_id, row_no, raw, result, matched_account_id, matched_stakeholder_id, message)
                    VALUES (:j, :n, CAST(:raw AS jsonb), :res, :acc, :st, :msg)""",
                    new MapSqlParameterSource().addValue("j", job).addValue("n", i++).addValue("raw", json.writeValueAsString(mapped))
                            .addValue("res", pv.result()).addValue("acc", pv.accountId()).addValue("st", pv.stakeholderId())
                            .addValue("msg", pv.message()));
        }
        jdbc.update("UPDATE crm.import_job SET status = 'READY' WHERE id = :j", Map.of("j", job));
        return summary(job);
    }

    static Map<String, String> map(Map<String, String> raw, Map<String, String> mapping) {
        Map<String, String> out = new LinkedHashMap<>();
        mapping.forEach((column, field) -> {
            String v = raw.get(column);
            Normalizer.clean(v).ifPresent(x -> out.put(field, x));
        });
        return out;
    }

    record Preview(String result, UUID accountId, UUID stakeholderId, String message) {}

    /** Dry run: CREATED (new company), MERGED (joins existing company), DUPLICATE (person exists), ERROR. */
    Preview preview(Map<String, String> r) {
        if (!r.containsKey("company")) return new Preview("ERROR", null, null, "Company is missing");
        if (!r.containsKey("fullName") && !r.containsKey("firstName")) return new Preview("ERROR", null, null, "Person name is missing");
        Optional<String> domain = Normalizer.domain(r.get("website"));
        UUID acc = domain.flatMap(d -> jdbc.queryForList("SELECT id FROM crm.account WHERE domain = :d", Map.of("d", d), UUID.class)
                .stream().findFirst()).orElse(null);
        if (acc == null) {
            acc = jdbc.queryForList("SELECT id FROM crm.account WHERE name_normalized = :n",
                    Map.of("n", Normalizer.companyName(r.get("company"))), UUID.class).stream().findFirst().orElse(null);
        }
        for (String key : List.of("email", "email2")) {
            Optional<String> e = Normalizer.email(r.get(key));
            if (e.isPresent()) {
                List<UUID> hit = jdbc.queryForList("SELECT stakeholder_id FROM crm.stakeholder_email WHERE email_normalized = :e",
                        Map.of("e", e.get()), UUID.class);
                if (!hit.isEmpty()) return new Preview("DUPLICATE", acc, hit.get(0), "Email already exists: " + e.get());
            }
        }
        Optional<String> li = Normalizer.linkedIn(r.get("linkedin"));
        if (li.isPresent()) {
            List<UUID> hit = jdbc.queryForList("SELECT id FROM crm.stakeholder WHERE linkedin_url = :l", Map.of("l", li.get()), UUID.class);
            if (!hit.isEmpty()) return new Preview("DUPLICATE", acc, hit.get(0), "LinkedIn profile already exists");
        }
        for (String key : List.of("phone", "phone2")) {
            Optional<String> ph = Normalizer.phoneE164(r.get(key));
            if (ph.isPresent()) {
                List<UUID> hit = jdbc.queryForList("SELECT stakeholder_id FROM crm.stakeholder_phone WHERE phone_e164 = :p",
                        Map.of("p", ph.get()), UUID.class);
                if (!hit.isEmpty()) return new Preview("REVIEW", acc, hit.get(0), "Phone matches an existing person: " + ph.get());
            }
        }
        return acc == null ? new Preview("CREATED", null, null, "New company") : new Preview("MERGED", acc, null, "Joins existing company");
    }

    /** Apply rows marked CREATED or MERGED (and REVIEW when includeReview). Each row in its own transaction. */
    public Map<String, Object> commit(UUID jobId, boolean includeReview, Actor actor) {
        jdbc.update("UPDATE crm.import_job SET status = 'IMPORTING' WHERE id = :j", Map.of("j", jobId));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, row_no, raw::text AS raw, result FROM crm.import_row WHERE import_job_id = :j ORDER BY row_no""", Map.of("j", jobId));
        int created = 0, merged = 0, errors = 0;
        for (Map<String, Object> row : rows) {
            String result = (String) row.get("result");
            boolean apply = result.equals("CREATED") || result.equals("MERGED") || (includeReview && result.equals("REVIEW"));
            if (!apply) continue;
            long rowId = ((Number) row.get("id")).longValue();
            try {
                Map<String, String> r = json.readValue((String) row.get("raw"), new TypeReference<Map<String, String>>() {});
                String outcome = rowTx.execute(st -> applyRow(r, actor, rowId));
                if ("CREATED".equals(outcome)) created++; else merged++;
            } catch (RuntimeException | IOException e) {
                errors++;
                jdbc.update("UPDATE crm.import_row SET result = 'ERROR', message = :m WHERE id = :id",
                        new MapSqlParameterSource().addValue("m", e.getMessage()).addValue("id", rowId));
            }
        }
        jdbc.update("""
                UPDATE crm.import_job SET status = 'DONE', created_rows = :c, merged_rows = :m, error_rows = :e, finished_at = now()
                 WHERE id = :j""", Map.of("c", created, "m", merged, "e", errors, "j", jobId));
        return summary(jobId);
    }

    String applyRow(Map<String, String> r, Actor actor, long rowId) {
        UUID owner = Optional.ofNullable(r.get("bdrEmail")).flatMap(users::findByEmail).map(u -> u.id())
                .orElse(actor.role() == Role.BDR ? actor.userId() : null);
        var res = accounts.resolveOrCreate(new AccountApi.CreateAccount(r.get("company"), r.get("website"), picklist("INDUSTRY", r.get("industry")),
                null, city(r.get("city")), integer(r.get("employeeCount")), integer(r.get("taxableEmployeeCount")),
                integer(r.get("licensePotential")), picklist("LEAD_SOURCE", r.get("leadSource")), r.get("apolloId")), actor);
        if (owner != null) accounts.addBdr(res.account().id(), owner);
        List<String> emails = new ArrayList<>();
        Optional.ofNullable(r.get("email")).ifPresent(emails::add);
        Optional.ofNullable(r.get("email2")).ifPresent(emails::add);
        List<String> phones = new ArrayList<>();
        Optional.ofNullable(r.get("phone")).ifPresent(phones::add);
        Optional.ofNullable(r.get("phone2")).ifPresent(phones::add);
        Department dept = Optional.ofNullable(r.get("department")).map(d -> {
            try { return Department.valueOf(d.toUpperCase()); } catch (IllegalArgumentException e) { return null; }
        }).orElse(null);
        var s = stakeholders.create(new StakeholderApi.CreateStakeholder(res.account().id(), r.get("firstName"), r.get("lastName"),
                r.get("fullName"), r.get("designation"), dept, null, emails, phones, r.get("linkedin"), null, null, owner,
                false, false, true), actor);
        if (r.get("remarks") != null) {
            activities.log(new ActivityApi.NewActivity(res.account().id(), s.id(), null, null, null, null, ActivityType.NOTE, null,
                    "Imported remark: " + r.get("remarks"), null, actor.userId()));
        }
        jdbc.update("UPDATE crm.import_row SET matched_account_id = :a, matched_stakeholder_id = :s WHERE id = :id",
                Map.of("a", res.account().id(), "s", s.id(), "id", rowId));
        audit.record("account", res.account().id(), AuditApi.Action.IMPORT, null, null, "row " + rowId, actor.userId());
        return res.created() ? "CREATED" : "MERGED";
    }

    UUID city(String name) {
        if (name == null) return null;
        return jdbc.queryForList("SELECT id FROM crm.city WHERE lower(name) = lower(:n) OR lower(region) = lower(:n) ORDER BY name LIMIT 1",
                Map.of("n", name.trim()), UUID.class).stream().findFirst().orElse(null);
    }

    UUID picklist(String key, String value) {
        if (value == null) return null;
        return jdbc.queryForList("""
                SELECT id FROM crm.picklist_value WHERE picklist_key = :k AND (lower(code) = lower(:v) OR lower(label) = lower(:v)) LIMIT 1""",
                Map.of("k", key, "v", value.trim()), UUID.class).stream().findFirst().orElse(null);
    }

    static Integer integer(String v) {
        if (v == null) return null;
        String d = v.replaceAll("[^0-9]", "");
        return d.isEmpty() ? null : Integer.valueOf(d);
    }

    public Map<String, Object> summary(UUID jobId) {
        Map<String, Object> job = new LinkedHashMap<>(jdbc.queryForList("""
                SELECT id, source, file_name AS "fileName", status, total_rows AS "totalRows", created_rows AS "created",
                       merged_rows AS "merged", error_rows AS "errors", created_at AS "createdAt", finished_at AS "finishedAt"
                  FROM crm.import_job WHERE id = :j""", Map.of("j", jobId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Import", jobId)));
        job.put("preview", jdbc.queryForList("SELECT result, count(*) AS n FROM crm.import_row WHERE import_job_id = :j GROUP BY result",
                Map.of("j", jobId)));
        return job;
    }

    /** Duplicate report: every row with its result and the matched record. */
    public List<Map<String, Object>> rows(UUID jobId) {
        return jdbc.queryForList("""
                SELECT r.row_no AS "row", r.result, r.message, r.raw->>'company' AS "company",
                       coalesce(r.raw->>'fullName', r.raw->>'firstName') AS "person", r.raw->>'email' AS "email",
                       a.name AS "matchedAccount", s.first_name || coalesce(' ' || s.last_name, '') AS "matchedStakeholder"
                  FROM crm.import_row r LEFT JOIN crm.account a ON a.id = r.matched_account_id
                  LEFT JOIN crm.stakeholder s ON s.id = r.matched_stakeholder_id
                 WHERE r.import_job_id = :j ORDER BY r.row_no""", Map.of("j", jobId));
    }
}
