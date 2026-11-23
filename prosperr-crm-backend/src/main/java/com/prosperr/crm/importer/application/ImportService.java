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
    /** Keeps one upload inside a single request on a small server; bigger lists go in batches. */
    public static final int MAX_ROWS = 5_000;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportService.class);

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

    /** Header name (lower-case, letters and digits only) -> CRM field. Covers Apollo exports and the team's own sheets. */
    static final Map<String, String> SYNONYMS = new LinkedHashMap<>();
    static {
        String[][] table = {
            {"company", "company", "companyname", "accountname", "account", "organization", "organisation", "organizationname", "employer", "firm", "client"},
            {"website", "website", "companywebsite", "websiteurl", "companyurl", "domain", "companydomain", "url", "web"},
            {"city", "city", "location", "companycity", "hqcity", "headquarters", "indiahq", "hq", "personcity", "town"},
            {"employeeCount", "employees", "employeecount", "noofemployees", "numberofemployees", "headcount", "estimatedemployees",
                    "estimatedindiaemployees", "linkedinemployees", "companysize", "teamsize", "staff"},
            {"licensePotential", "licenses", "licences", "license", "licence", "licensepotential", "licencepotential", "seats", "licensecount"},
            {"industry", "industry", "sector", "vertical", "subindustry", "companyindustry"},
            {"leadSource", "leadsource", "source", "leadorigin", "channel"},
            {"apolloId", "apolloaccountid", "apolloid", "accountid"},
            {"firstName", "firstname", "fname", "givenname"},
            {"lastName", "lastname", "lname", "surname", "familyname"},
            {"fullName", "name", "fullname", "contactname", "contact", "pocname", "poc", "person", "personname", "stakeholder",
                    "stakeholdername", "decisionmaker", "prospect", "prospectname", "lead", "leadname"},
            {"designation", "title", "designation", "jobtitle", "role", "position", "persontitle", "pocdesignation"},
            {"department", "department", "dept", "function", "departments"},
            {"email", "email", "emailaddress", "workemail", "businessemail", "email1", "emailid", "mail", "contactemail", "officialemail", "pocemail"},
            {"email2", "email2", "secondaryemail", "personalemail", "alternateemail", "otheremail"},
            {"phone", "phone", "mobile", "mobilephone", "mobilenumber", "mobileno", "phonenumber", "phoneno", "contactnumber", "contactno",
                    "number", "workdirectphone", "directphone", "cell", "cellphone", "whatsapp", "pocphone", "pocnumber"},
            {"phone2", "phone2", "corporatephone", "companyphone", "otherphone", "homephone", "alternatephone", "landline", "officephone"},
            {"linkedin", "linkedin", "linkedinurl", "personlinkedinurl", "linkedinprofile", "linkedinprofileurl", "linkedinlink", "profileurl", "linkedinid"},
            {"bdrEmail", "bdremail", "owneremail", "assignedtoemail", "contactowneremail", "sdremail"},
            {"remarks", "remarks", "remark", "notes", "note", "comments", "comment", "update", "status", "feedback", "summary"},
        };
        for (String[] row : table) for (int i = 1; i < row.length; i++) SYNONYMS.putIfAbsent(row[i], row[0]);
    }

    static String key(String header) { return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** Best guess of file column -> field, each field used at most once (first matching column wins). */
    static Map<String, String> suggest(List<String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (String h : headers) {                                   // exact synonyms first
            String f = SYNONYMS.get(key(h));
            if (f != null && used.add(f)) out.put(h, f);
        }
        for (String h : headers) {                                   // then "contains" (e.g. "Company Name for Emails", "POC Mobile No.")
            if (out.containsKey(h)) continue;
            String k = key(h);
            if (k.matches(".*(status|verified|confidence|count|score|date|catchall).*") && !k.contains("employee")) continue;  // metadata about a field, not the field
            String f = k.contains("linkedin") ? "linkedin"
                    : k.contains("company") || k.contains("account") ? "company"
                    : k.contains("email") ? (used.contains("email") ? "email2" : "email")
                    : k.contains("phone") || k.contains("mobile") ? (used.contains("phone") ? "phone2" : "phone")
                    : k.contains("designation") || k.endsWith("title") ? "designation"
                    : k.contains("employee") ? "employeeCount" : k.contains("licen") ? "licensePotential" : k.contains("industry") ? "industry"
                    : k.contains("city") || k.contains("location") ? "city" : k.contains("website") || k.contains("domain") ? "website"
                    : k.contains("remark") || k.contains("note") || k.contains("comment") ? "remarks" : null;
            if (f != null && used.add(f)) out.put(h, f);
        }
        if (out.containsValue("firstName") && out.containsValue("fullName") && !out.containsValue("lastName")) {
            out.values().remove("firstName");                         // a single "Name" column is safer than a lone first name
        }
        return out;
    }

    /** Step 1 of the import wizard: sheets, header row, a few sample rows and suggested column matches. Nothing is saved. */
    public Map<String, Object> inspect(String fileName, byte[] bytes, String sheet) throws IOException {
        RowReader reader = readers.stream().filter(r -> r.supports(fileName)).findFirst()
                .orElseThrow(() -> new DomainException("FILE_TYPE", "Upload a .csv or .xlsx file (in Excel: File > Save As > .xlsx or CSV UTF-8)"));
        List<String> sheets;
        List<Map<String, String>> rows;
        try {
            sheets = reader.sheets(new java.io.ByteArrayInputStream(bytes));
            String pick = sheet != null && sheets.contains(sheet) ? sheet : sheets.isEmpty() ? null : sheets.get(0);
            rows = reader.read(new java.io.ByteArrayInputStream(bytes), pick);
            sheet = pick;
        } catch (RuntimeException e) {
            throw new DomainException("FILE_UNREADABLE", "This file couldn't be read. Save it again as .xlsx or CSV (UTF-8) and retry.");
        }
        rows.removeIf(r -> r.values().stream().allMatch(v -> v == null || v.isBlank()));
        List<String> headers = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        out.put("sheet", sheet);
        out.put("headers", headers);
        out.put("rows", rows.size());
        out.put("sample", rows.stream().limit(5).toList());
        out.put("suggested", suggest(headers));
        out.put("maxRows", MAX_ROWS);
        return out;
    }

    @Transactional
    public Map<String, Object> upload(String fileName, InputStream in, String source, Map<String, String> mapping, Actor actor)
            throws IOException {
        return upload(fileName, in, source, mapping, null, null, actor);
    }

    /** sheet: which Excel sheet; ownerBdrId: BDR who gets rows that have no owner column (managers importing for a BDR). */
    @Transactional
    public Map<String, Object> upload(String fileName, InputStream in, String source, Map<String, String> mapping, String sheet,
                                      UUID ownerBdrId, Actor actor) throws IOException {
        if (actor.role() == Role.AE) throw new DomainException("FORBIDDEN_IMPORT", "AEs cannot import prospects");
        for (String f : mapping.values()) if (!FIELDS.contains(f)) throw new DomainException("UNKNOWN_FIELD", "Unknown field " + f);
        RowReader reader = readers.stream().filter(r -> r.supports(fileName)).findFirst()
                .orElseThrow(() -> new DomainException("FILE_TYPE", "Upload a .csv or .xlsx file"));
        if (!mapping.containsValue("company")) throw new DomainException("MAPPING_INCOMPLETE", "Map a column to Company before importing");
        if (!mapping.containsValue("fullName") && !mapping.containsValue("firstName"))
            throw new DomainException("MAPPING_INCOMPLETE", "Map a column to the person's name (full name or first name)");
        List<Map<String, String>> raw;
        String ownerEmail = null;
        if (ownerBdrId != null) {
            if (!actor.isLeadership() && !ownerBdrId.equals(actor.userId()))
                throw new DomainException("FORBIDDEN_IMPORT", "Only a manager can import leads for another BDR");
            var u = users.get(ownerBdrId);
            if (u == null || u.role() != Role.BDR || !u.active()) throw new DomainException("BAD_OWNER", "Pick an active BDR as the owner");
            ownerEmail = u.email();
        }
        try { raw = new ArrayList<>(reader.read(in, sheet)); }
        catch (RuntimeException | IOException e) {
            throw new DomainException("FILE_UNREADABLE", "This file couldn't be read. Save it again as .csv (UTF-8) or .xlsx and retry.");
        }
        raw.removeIf(r -> r.values().stream().allMatch(v -> v == null || v.isBlank()));   // blank spreadsheet rows
        if (raw.isEmpty()) throw new DomainException("IMPORT_EMPTY", "The file has no data rows");
        if (raw.size() > MAX_ROWS)
            throw new DomainException("IMPORT_TOO_LARGE", "This file has " + raw.size() + " rows. Split it into files of up to " + MAX_ROWS + " rows.");
        UUID job = jdbc.queryForObject("""
                INSERT INTO crm.import_job (source, file_name, column_mapping, status, total_rows, created_by)
                VALUES (:s, :f, CAST(:m AS jsonb), 'VALIDATING', :n, :by) RETURNING id""",
                new MapSqlParameterSource().addValue("s", source == null ? "MANUAL_UPLOAD" : source).addValue("f", fileName)
                        .addValue("m", json.writeValueAsString(mapping)).addValue("n", raw.size()).addValue("by", actor.userId()), UUID.class);
        int i = 1;
        Map<String, Integer> seen = new HashMap<>();   // same person twice in one file
        Map<String, Integer> companies = new HashMap<>();   // first row of each new company in this file
        for (Map<String, String> r : raw) {
            Map<String, String> mapped = map(r, mapping);
            if (ownerEmail != null) mapped.putIfAbsent("bdrEmail", ownerEmail);
            if ("APOLLO".equalsIgnoreCase(source)) mapped.putIfAbsent("leadSource", "APOLLO");
            Preview pv = withinFile(mapped, i, seen);
            if (pv == null) pv = preview(mapped);
            if ("CREATED".equals(pv.result()) && mapped.get("company") != null) {
                Integer first = companies.putIfAbsent(Normalizer.companyName(mapped.get("company")), i);
                if (first != null) pv = new Preview("MERGED", null, null, "Joins the new company from row " + first + pv.message().substring("New company".length()));
            }
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
        for (String key : List.of("email", "email2"))
            if (r.get(key) != null && Normalizer.email(r.get(key)).isEmpty()) return new Preview("ERROR", null, null, "Invalid email: " + r.get(key));
        for (String key : List.of("phone", "phone2"))
            if (r.get(key) != null && Normalizer.phoneE164(r.get(key)).isEmpty()) return new Preview("ERROR", null, null, "Invalid phone: " + r.get(key));
        if (r.get("bdrEmail") != null) {
            var bdr = users.findByEmail(r.get("bdrEmail").trim());
            if (bdr.isEmpty() || bdr.get().role() != Role.BDR || !bdr.get().active())
                return new Preview("ERROR", null, null, "Owner " + r.get("bdrEmail") + " is not an active BDR");
        }
        for (String key : List.of("employeeCount", "taxableEmployeeCount", "licensePotential"))
            if (r.get(key) != null && (integer(r.get(key)) == null || integer(r.get(key)) > 5_000_000))
                return new Preview("ERROR", null, null, "Not a valid number for " + key + ": " + r.get(key));
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
        String warn = warnings(r);
        return acc == null ? new Preview("CREATED", null, null, "New company" + warn) : new Preview("MERGED", acc, null, "Joins existing company" + warn);
    }

    /** Values that don't match a configured city or list are left blank; say so in the preview instead of silently. */
    String warnings(Map<String, String> r) {
        List<String> w = new ArrayList<>();
        if (r.get("city") != null && city(r.get("city")) == null) w.add("city '" + r.get("city") + "' not found");
        if (r.get("industry") != null && picklist("INDUSTRY", r.get("industry")) == null) w.add("industry '" + r.get("industry") + "' not found");
        if (r.get("leadSource") != null && picklist("LEAD_SOURCE", r.get("leadSource")) == null) w.add("lead source '" + r.get("leadSource") + "' not found");
        return w.isEmpty() ? "" : " · " + String.join(", ", w) + " (left blank)";
    }

    /** Flags a row whose email, phone or LinkedIn already appeared earlier in the same file. */
    static Preview withinFile(Map<String, String> r, int rowNo, Map<String, Integer> seen) {
        List<String> keys = new ArrayList<>();
        for (String k : List.of("email", "email2")) Normalizer.email(r.get(k)).ifPresent(e -> keys.add("e:" + e));
        for (String k : List.of("phone", "phone2")) Normalizer.phoneE164(r.get(k)).ifPresent(p -> keys.add("p:" + p));
        Normalizer.linkedIn(r.get("linkedin")).ifPresent(l -> keys.add("l:" + l));
        for (String k : keys) {
            Integer first = seen.get(k);
            if (first != null) return new Preview("DUPLICATE", null, null, "Same person as row " + first + " in this file");
        }
        keys.forEach(k -> seen.put(k, rowNo));
        return null;
    }

    /** Apply rows marked CREATED or MERGED (and REVIEW when includeReview). Each row in its own transaction. */
    public Map<String, Object> commit(UUID jobId, boolean includeReview, Actor actor) {
        checkOwner(jobId, actor);
        // Only one commit per job: a double click or a second tab gets a clear error instead of duplicate records.
        int claimed = jdbc.update("UPDATE crm.import_job SET status = 'IMPORTING' WHERE id = :j AND status = 'READY'", Map.of("j", jobId));
        if (claimed == 0) throw new DomainException("IMPORT_NOT_READY", "This import was already run or is still running");
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
                String msg = e instanceof DomainException ? e.getMessage() : "Couldn't import this row";
                if (!(e instanceof DomainException)) log.warn("Import {} row {} failed", jobId, rowId, e);
                jdbc.update("UPDATE crm.import_row SET result = 'ERROR', message = :m WHERE id = :id",
                        new MapSqlParameterSource().addValue("m", msg).addValue("id", rowId));
            }
        }
        jdbc.update("""
                UPDATE crm.import_job SET status = 'DONE', created_rows = :c, merged_rows = :m, error_rows = :e, finished_at = now()
                 WHERE id = :j""", Map.of("c", created, "m", merged, "e", errors, "j", jobId));
        return summary(jobId);
    }

    String applyRow(Map<String, String> r, Actor actor, long rowId) {
        UUID owner = Optional.ofNullable(r.get("bdrEmail")).map(String::trim).flatMap(users::findByEmail)
                .filter(u -> u.role() == Role.BDR && u.active()).map(u -> u.id())
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

    /** Only the person who uploaded a file (or a manager/admin) can see or run it. */
    public void checkOwner(UUID jobId, Actor actor) {
        UUID by = jdbc.queryForList("SELECT created_by FROM crm.import_job WHERE id = :j", Map.of("j", jobId), UUID.class)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Import", jobId));
        if (!actor.isLeadership() && !actor.userId().equals(by))
            throw new com.prosperr.crm.shared.web.ForbiddenException("This import belongs to someone else");
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
                       coalesce(r.raw->>'fullName', trim(coalesce(r.raw->>'firstName', '') || ' ' || coalesce(r.raw->>'lastName', ''))) AS "person", r.raw->>'email' AS "email",
                       a.name AS "matchedAccount", s.first_name || coalesce(' ' || s.last_name, '') AS "matchedStakeholder"
                  FROM crm.import_row r LEFT JOIN crm.account a ON a.id = r.matched_account_id
                  LEFT JOIN crm.stakeholder s ON s.id = r.matched_stakeholder_id
                 WHERE r.import_job_id = :j ORDER BY r.row_no""", Map.of("j", jobId));
    }
}
