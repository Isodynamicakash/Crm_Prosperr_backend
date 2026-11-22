package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.identity.application.TokenService;
import com.prosperr.crm.identity.application.UserService;
import com.prosperr.crm.identity.application.GoogleTokenVerifier;
import com.prosperr.crm.shared.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base for end-to-end tests: real Spring context, real Postgres, HTTP calls through MockMvc with real JWTs.
 * Database: Testcontainers (Docker) by default, or set CRM_TEST_DB_URL / CRM_TEST_DB_USER / CRM_TEST_DB_PASSWORD
 * to an EMPTY Postgres database (e.g. jdbc:postgresql://localhost:5432/crm_test?currentSchema=crm).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.Beans.class)
public abstract class IntegrationTest {
    static PostgreSQLContainer<?> pg;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        String url = System.getenv("CRM_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> Objects.requireNonNullElse(System.getenv("CRM_TEST_DB_USER"), "postgres"));
            r.add("spring.datasource.password", () -> Objects.requireNonNullElse(System.getenv("CRM_TEST_DB_PASSWORD"), "postgres"));
            return;
        }
        if (pg == null) {
            pg = new PostgreSQLContainer<>("postgres:17-alpine");
            pg.start();
        }
        r.add("spring.datasource.url", () -> pg.getJdbcUrl() + (pg.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=crm");
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @TestConfiguration
    static class Beans {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean @Primary FakeMailGateway fakeMail() { return new FakeMailGateway(); }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate db;
    @Autowired protected UserService users;
    @Autowired protected TokenService tokens;
    @Autowired protected MutableClock clock;
    @Autowired protected FakeMailGateway mail;
    @MockitoBean protected GoogleTokenVerifier google;

    protected UUID admin, manager, bdr1, bdr2, ae1, ae2;
    private final Map<UUID, String> tokenCache = new HashMap<>();

    static final String[] TRANSACTIONAL_TABLES = {"import_row", "import_job", "notification", "target", "audit_log",
            "temperature_history", "account_status_history", "stage_history", "assignment_history", "task", "activity",
            "email_message", "sequence_enrollment", "opportunity_checklist", "attachment", "meeting_attendee", "meeting",
            "qualification_correction", "sql_criterion_result", "sql_submission", "opportunity", "stakeholder_phone",
            "stakeholder_email", "stakeholder", "account_bdr", "account", "saved_view", "user_mailbox"};

    @BeforeEach
    void cleanAndSeed() {
        db.update("UPDATE crm.app_setting SET updated_by = NULL");
        db.update("UPDATE crm.icp_rule SET updated_by = NULL");
        db.update("UPDATE crm.email_template SET updated_by = NULL");
        db.update("DELETE FROM crm.role_field_permission");
        db.update("TRUNCATE " + String.join(", ", Arrays.stream(TRANSACTIONAL_TABLES).map(t -> "crm." + t).toList()));
        // app_user is deleted, not truncated: Postgres refuses TRUNCATE while seed tables
        // (app_setting, icp_rule, email_template, role_field_permission) hold a foreign key to it.
        db.update("DELETE FROM crm.app_user");
        db.update("UPDATE crm.app_setting SET value = 'false' WHERE key = 'require_inactive_approval'");
        tokenCache.clear();
        mail.reset();
        clock.set(new MutableClock().instant());
        admin = user(Role.ADMIN, "Admin");
        manager = user(Role.MANAGER, "Lead");
        bdr1 = user(Role.BDR, "Jasim");
        bdr2 = user(Role.BDR, "Paramita");
        ae1 = user(Role.AE, "Mahesh");
        ae2 = user(Role.AE, "Kavya");
    }

    protected UUID user(Role role, String name) {
        return users.create(new UserService.CreateUser(name.toLowerCase() + "@prosperr.io", name + " Test", "+91 98000 0000" + role.ordinal(),
                role, null)).id();
    }

    protected String token(UUID userId) {
        return tokenCache.computeIfAbsent(userId, id -> tokens.issue(users.get(id)).accessToken());
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected JsonNode call(MockHttpServletRequestBuilder req, UUID as, Object body, int expected) throws Exception {
        if (as != null) req.header("Authorization", "Bearer " + token(as));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        MvcResult res = mvc.perform(req).andReturn();
        String content = res.getResponse().getContentAsString();
        assertEquals(expected, res.getResponse().getStatus(), "Response: " + content);
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }

    protected JsonNode get(String url, UUID as) throws Exception { return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), as, null, 200); }
    protected JsonNode post(String url, UUID as, Object body) throws Exception { return post(url, as, body, 200); }
    protected JsonNode post(String url, UUID as, Object body, int status) throws Exception {
        meetingHappened(url);
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), as, body, status);
    }

    /** Outcomes are only accepted once a meeting has started, so tests that record one move the meeting into the past first. */
    private void meetingHappened(String url) {
        var m = java.util.regex.Pattern.compile("^/api/meetings/([0-9a-f-]{36})/outcome$").matcher(url);
        if (m.matches()) db.update("UPDATE crm.meeting SET scheduled_start = now() - interval '1 hour', scheduled_end = now() - interval '30 minutes' "
                + "WHERE id = ?::uuid AND scheduled_start > now()", m.group(1));
    }

    /** Calls the outcome endpoint without moving the meeting, to test the "not started yet" guard. */
    protected JsonNode postOutcomeAsScheduled(String url, UUID as, Object body, int status) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), as, body, status);
    }
    protected JsonNode patch(String url, UUID as, Object body) throws Exception { return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url), as, body, 200); }
    protected JsonNode put(String url, UUID as, Object body) throws Exception { return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url), as, body, 200); }
    protected JsonNode put(String url, UUID as, Object body, int status) throws Exception { return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url), as, body, status); }

    // ------------------------------------------------------------------ reference data

    protected UUID pick(String key, String code) {
        return db.queryForObject("SELECT id FROM crm.picklist_value WHERE picklist_key = ? AND code = ?", UUID.class, key, code);
    }

    protected UUID city(String name) { return db.queryForObject("SELECT id FROM crm.city WHERE name = ?", UUID.class, name); }

    protected UUID checklistItem(String stage, String code) {
        return db.queryForObject("SELECT id FROM crm.stage_checklist_item WHERE stage = ? AND code = ?", UUID.class, stage, code);
    }

    protected Instant inDays(int days) { return clock.instant().plusSeconds(86400L * days); }

    protected LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Kolkata")); }

    // ------------------------------------------------------------------ business flows

    protected UUID account(UUID as, String name, String website, String cityName, Integer licenses) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("name", name);
        b.put("website", website);
        b.put("cityId", cityName == null ? null : city(cityName));
        b.put("licensePotential", licenses);
        return UUID.fromString(post("/api/accounts", as, b).path("account").path("id").asText());
    }

    protected JsonNode stakeholder(UUID as, UUID accountId, String fullName, String designation, String email, String phone,
                                   UUID referredBy) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("fullName", fullName);
        b.put("designation", designation);
        b.put("emails", email == null ? List.of() : List.of(email));
        b.put("phones", phone == null ? List.of() : List.of(phone));
        b.put("referredById", referredBy);
        return post("/api/accounts/" + accountId + "/stakeholders", as, b);
    }

    protected JsonNode logCall(UUID as, UUID stakeholderId, Map<String, Object> body) throws Exception {
        return post("/api/stakeholders/" + stakeholderId + "/calls", as, body);
    }

    protected Map<String, Object> bant() {
        return Map.of("budgetId", pick("BANT_BUDGET", "COST_NEUTRAL"), "authorityId", pick("BANT_AUTHORITY", "IDENTIFIED"),
                "needId", pick("BANT_NEED", "FBP"), "timelineId", pick("BANT_TIMELINE", "THIS_QUARTER"));
    }

    protected Map<String, Object> sqlBody(UUID stakeholderId, Map<String, Object> extra) {
        Map<String, Object> b = new HashMap<>();
        b.put("qualifyingStakeholderId", stakeholderId);
        b.put("businessRelevance", "FBP");
        b.put("bant", bant());
        b.put("l1", Map.of("scheduledStart", inDays(2).toString(), "scheduledEnd", inDays(2).plusSeconds(1800).toString()));
        b.putAll(extra);
        return b;
    }

    /** Prospect -> qualified SQL -> assigned to AE. Returns {account, stakeholder, meeting, opportunity}. */
    protected Map<String, UUID> qualifiedAccount(String name, int licenses, UUID ae) throws Exception {
        UUID acc = account(bdr1, name, name.toLowerCase().replace(" ", "") + ".com", "Bengaluru", licenses);
        UUID cfo = UUID.fromString(stakeholder(bdr1, acc, "Rita Sharma", "CFO", "rita@" + name.toLowerCase().replace(" ", "") + ".com",
                null, null).path("id").asText());
        logCall(bdr1, cfo, Map.of("outcomeCode", "L1_BOOKED"));
        JsonNode sub = post("/api/accounts/" + acc + "/sql", bdr1, sqlBody(cfo, Map.of()));
        UUID submission = UUID.fromString(sub.path("submissionId").asText());
        JsonNode v = post("/api/sql/" + submission + "/validate", manager, Map.of("approve", true));
        post("/api/accounts/" + acc + "/assign", manager, Map.of("aeId", ae));
        Map<String, UUID> out = new HashMap<>();
        out.put("account", acc);
        out.put("stakeholder", cfo);
        out.put("meeting", UUID.fromString(sub.path("meetingId").asText()));
        out.put("opportunity", UUID.fromString(v.path("opportunityId").asText()));
        out.put("submission", submission);
        return out;
    }

    protected String stage(UUID accountId) {
        return db.queryForObject("SELECT lifecycle_stage FROM crm.account WHERE id = ?", String.class, accountId);
    }
}