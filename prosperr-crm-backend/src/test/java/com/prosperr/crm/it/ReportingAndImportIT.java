package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PRD dashboards, reports, M9 targets, M10-2 audit, M10-3 export, M1-1 import with duplicate report. */
class ReportingAndImportIT extends IntegrationTest {

    @Test
    void Dashboards_bdrAeLeadershipHygieneAllAnswer() throws Exception {
        var q = qualifiedAccount("Dash Co", 600, ae1);
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        JsonNode bdr = get("/api/dashboards/bdr?preset=30", bdr1);
        assertEquals(1, bdr.path("sqlsSubmitted").asInt());
        assertEquals(1, bdr.path("sqlsApproved").asInt());
        assertEquals(1, bdr.path("l1Booked").asInt());
        JsonNode ae = get("/api/dashboards/ae", ae1);
        assertEquals(1, ae.path("active").asInt());
        assertEquals(1, ae.path("l2Pending").asInt());
        JsonNode lead = get("/api/dashboards/leadership?preset=30", manager);
        assertEquals("Leads", lead.path("funnel").get(0).path("stage").asText());
        assertEquals(1, lead.path("funnel").get(2).path("count").asInt(), "one account reached L1");
        get("/api/dashboards/hygiene", manager);
        call(MockMvcRequestBuilders.get("/api/dashboards/leadership"), bdr1, null, 403);
        call(MockMvcRequestBuilders.get("/api/dashboards/ae"), bdr1, null, 403);
    }

    @Test
    void Pulse_dailySeriesOutcomeMixAndFeed_scopedByRole() throws Exception {
        var q = qualifiedAccount("Pulse Co", 600, ae1);
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        JsonNode mine = get("/api/dashboards/pulse?preset=7", bdr1);
        assertEquals(7, mine.path("series").size(), "one point per day in the period");
        int calls = 0, sqls = 0;
        for (JsonNode d : mine.path("series")) { calls += d.path("calls").asInt(); sqls += d.path("sqls").asInt(); }
        assertTrue(calls >= 1, "the L1-booked call is counted");
        assertEquals(1, sqls);
        assertTrue(mine.path("outcomeMix").size() >= 1);
        assertTrue(mine.path("feed").size() >= 1);
        assertEquals(0, sum(get("/api/dashboards/pulse?preset=7", bdr2).path("series"), "sqls"), "another BDR sees only their own work");
        assertEquals(1, sum(get("/api/dashboards/pulse?preset=7", manager).path("series"), "sqls"), "leadership sees the team");
        assertTrue(sum(get("/api/dashboards/pulse?preset=7", ae1).path("series"), "meetingsDone") >= 1, "the AE's completed meeting counts");
    }

    @Test
    void Dashboards_showThisMonthsTargets_evenWhenThePeriodStartsLastMonth() throws Exception {
        String range = "?from=" + today().minusMonths(1).withDayOfMonth(1) + "&to=" + today();
        put("/api/targets", manager, Map.of("userId", bdr1, "periodType", "MONTH", "periodDate", today().withDayOfMonth(1).toString(), "metric", "L1_BOOKED", "value", 10));
        put("/api/targets", manager, Map.of("userId", ae1, "periodType", "MONTH", "periodDate", today().withDayOfMonth(1).toString(), "metric", "WON", "value", 2));
        assertEquals(1, get("/api/dashboards/bdr" + range, bdr1).path("targets").size(), "the period starts last month, targets are this month's");
        assertEquals(1, get("/api/dashboards/ae" + range, ae1).path("targets").size());
    }

    private static int sum(JsonNode series, String field) {
        int n = 0;
        for (JsonNode d : series) n += d.path(field).asInt();
        return n;
    }

    @Test
    void Dashboards_everyNumberOpensItsAccounts_andExportsCsv() throws Exception {
        qualifiedAccount("Drill Co", 600, ae1);
        assertEquals(1, get("/api/dashboards/drilldown?metric=activePipeline", manager).size());
        var res = mvc.perform(MockMvcRequestBuilders.get("/api/accounts?format=csv").header("Authorization", "Bearer " + token(manager))).andReturn();
        assertEquals(200, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentType().startsWith("text/csv"));
        assertTrue(res.getResponse().getContentAsString().contains("Drill Co"));
        var xlsx = mvc.perform(MockMvcRequestBuilders.get("/api/accounts?format=xlsx").header("Authorization", "Bearer " + token(manager))).andReturn();
        assertTrue(xlsx.getResponse().getContentAsByteArray().length > 100);
    }

    @Test
    void Report1_l1DoneButNotL2_listedWithSegments() throws Exception {
        var q = qualifiedAccount("Stall Co", 600, ae1);
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_FOLLOW_UP"));
        JsonNode r = get("/api/reports/l1-not-progressed", manager);
        assertEquals(1, r.size());
        assertEquals("Stall Co", r.get(0).path("account").asText());
        assertTrue(r.get(0).has("spocDesignation"));
        assertTrue(r.get(0).has("multithreadingStarted"));
        get("/api/reports/stakeholder-coverage", manager);
        get("/api/reports/inactive-reasons", manager);
        get("/api/reports/workload", manager);
        get("/api/reports/ageing", manager);
        get("/api/reports/sql-quality", manager);
        get("/api/reports/sequence-performance", manager);
    }

    @Test
    void M9_targetsSetByManager_achievementLive() throws Exception {
        var q = qualifiedAccount("Target Co", 600, ae1);
        put("/api/targets", manager, Map.of("userId", bdr1, "periodType", "MONTH", "periodDate", today().toString(), "metric", "L1_BOOKED", "value", 10));
        put("/api/targets", bdr1, Map.of("userId", bdr1, "periodType", "MONTH", "periodDate", today().toString(), "metric", "L1_BOOKED", "value", 1), 403);
        JsonNode a = get("/api/targets/achievement?period=MONTH&date=" + today(), bdr1);
        assertEquals(1, a.size());
        assertEquals(10, a.get(0).path("target").asInt());
    }

    @Test
    void M10_2_auditTrailForOwnerStageStatusSqlChanges() throws Exception {
        var q = qualifiedAccount("Audit Co", 600, ae1);
        JsonNode a = get("/api/audit/account/" + q.get("account"), manager);
        assertTrue(a.size() >= 2, "created + AE owner");
        assertTrue(get("/api/audit/sql_submission/" + q.get("submission"), manager).size() >= 2);
        call(MockMvcRequestBuilders.get("/api/audit/account/" + q.get("account")), bdr1, null, 403);
    }

    @Test
    void M1_1_csvImportWithMappingAndDuplicateReport() throws Exception {
        account(bdr1, "Existing Co", "existing.co", "Bengaluru", 500);
        stakeholder(bdr1, db.queryForObject("SELECT id FROM crm.account", java.util.UUID.class), "Old Person", "CFO", "old@existing.co", null, null);
        String csv = "Company,Website,City,Licenses,Name,Title,Email,Mobile,LinkedIn\n"
                + "New Co,https://newco.in,Bengaluru,700,Rita Sharma,CFO,rita@newco.in,98765 11111,linkedin.com/in/rita?trk=1\n"
                + "Existing Co,existing.co,Gurugram,500,Asha Rao,CHRO,asha@existing.co,NA,\n"
                + "Existing Co,existing.co,,,Old Person,CFO,OLD@existing.co,,\n"
                + ",,,,Nobody,,,,\n";
        String mapping = json.writeValueAsString(Map.of("Company", "company", "Website", "website", "City", "city",
                "Licenses", "licensePotential", "Name", "fullName", "Title", "designation", "Email", "email",
                "Mobile", "phone", "LinkedIn", "linkedin"));
        var res = mvc.perform(MockMvcRequestBuilders.multipart("/api/imports").file(new MockMultipartFile("file", "leads.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8))).param("mapping", mapping)
                .header("Authorization", "Bearer " + token(bdr1))).andReturn();
        assertEquals(200, res.getResponse().getStatus(), res.getResponse().getContentAsString());
        String job = json.readTree(res.getResponse().getContentAsString()).path("id").asText();
        JsonNode rows = get("/api/imports/" + job + "/rows", bdr1);
        assertEquals("CREATED", rows.get(0).path("result").asText());
        assertEquals("MERGED", rows.get(1).path("result").asText());
        assertEquals("DUPLICATE", rows.get(2).path("result").asText());
        assertEquals("ERROR", rows.get(3).path("result").asText());
        JsonNode done = post("/api/imports/" + job + "/commit", bdr1, null);
        assertEquals(1, done.path("created").asInt());
        assertEquals(1, done.path("merged").asInt());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM crm.account", Integer.class));
        assertEquals("https://www.linkedin.com/in/rita",
                db.queryForObject("SELECT linkedin_url FROM crm.stakeholder WHERE first_name = 'Rita'", String.class));
        assertEquals("+919876511111", db.queryForObject("SELECT phone_e164 FROM crm.stakeholder_phone", String.class));
    }
}
