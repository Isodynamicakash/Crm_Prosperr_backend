package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M4 SQL qualification, BRD 4 and Stage 2. */
class QualificationIT extends IntegrationTest {

    UUID prospect(String name, String cityName, int licenses, String designation) throws Exception {
        UUID a = account(bdr1, name, name.toLowerCase().replace(" ", "") + ".com", cityName, licenses);
        UUID s = UUID.fromString(stakeholder(bdr1, a, "Person " + name, designation, null, null, null).path("id").asText());
        logCall(bdr1, s, Map.of("outcomeCode", "L1_BOOKED"));
        db.update("UPDATE crm.app_setting SET value = 'true' WHERE key = 'prospecting_history_visible_after_l1'");
        return s;
    }

    UUID accountOf(UUID stakeholder) {
        return db.queryForObject("SELECT account_id FROM crm.stakeholder WHERE id = ?", UUID.class, stakeholder);
    }

    @Test
    void M4_1_checklistIsAutoCheckedFromAccountAndStakeholder() throws Exception {
        UUID s = prospect("Check Co", "Bengaluru", 620, "CFO");
        JsonNode c = post("/api/accounts/" + accountOf(s) + "/sql/checklist", bdr1, sqlBody(s, Map.of()));
        assertTrue(c.path("canSubmit").asBoolean());
        assertEquals(4, c.path("criteria").size());
    }

    @Test
    void M4_3_M4_10_mandatoryFieldsAndBantValidatedBeforeSubmit() throws Exception {
        UUID s = prospect("Bant Co", "Bengaluru", 620, "CFO");
        Map<String, Object> noBant = sqlBody(s, Map.of());
        noBant.remove("bant");
        JsonNode err = post("/api/accounts/" + accountOf(s) + "/sql", bdr1, noBant, 422);
        assertEquals("SQL_MISSING_FIELDS", err.path("code").asText());
        assertTrue(err.path("detail").asText().contains("bant"));
    }

    @Test
    void M4_4_submitBooksL1_entersSharedZone_statusMeetingBooked_managerNotified() throws Exception {
        UUID s = prospect("Book Co", "Delhi", 620, "Head of Finance");
        UUID a = accountOf(s);
        JsonNode r = post("/api/accounts/" + a + "/sql", bdr1, sqlBody(s, Map.of()));
        assertEquals("PIPELINE", db.queryForObject("SELECT visibility_zone FROM crm.account WHERE id = ?", String.class, a));
        assertEquals("MEETING_BOOKED", db.queryForObject("SELECT status FROM crm.sql_submission WHERE id = ?::uuid", String.class, r.path("submissionId").asText()));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.meeting WHERE account_id = ? AND level = 'L1'", Integer.class, a));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE user_id = ? AND type = 'SQL_SUBMITTED'", Integer.class, manager));
        assertEquals("PROSPECT", stage(a), "booking an L1 alone does not make it an SQL stage");
        // pipeline zone: any AE can now view it (permission table)
        get("/api/accounts/" + a, ae2);
    }

    @Test
    void M4_rightStakeholder_juniorDirectContactFails() throws Exception {
        UUID s = prospect("Junior Co", "Bengaluru", 620, "HR Manager");
        JsonNode err = post("/api/accounts/" + accountOf(s) + "/sql", bdr1, sqlBody(s, Map.of()), 422);
        assertEquals("SQL_CRITERIA_FAILED", err.path("code").asText());
        assertTrue(err.path("detail").asText().contains("RIGHT_STAKEHOLDER"));
    }

    @Test
    void M4_locationOutsideIcpFails_and_M4_7_p1NeedsHighIntentAndManagerConfirm() throws Exception {
        db.update("INSERT INTO crm.city (name, icp_priority) VALUES ('Pune', 'NONE')");
        UUID pune = prospect("Pune Co", "Pune", 620, "CFO");
        assertTrue(post("/api/accounts/" + accountOf(pune) + "/sql", bdr1, sqlBody(pune, Map.of()), 422).path("detail").asText().contains("LOCATION"));

        UUID mum = prospect("Mumbai Co", "Mumbai", 620, "CFO");
        post("/api/accounts/" + accountOf(mum) + "/sql", bdr1, sqlBody(mum, Map.of()), 422);
        JsonNode ok = post("/api/accounts/" + accountOf(mum) + "/sql", bdr1, sqlBody(mum, Map.of("intentRating", "HIGH",
                "intentReason", "Budget approved for Q1")));
        assertTrue(ok.path("needsApproval").asBoolean());
        String sub = ok.path("submissionId").asText();
        assertEquals("SQL_NOT_QUALIFIED", post("/api/sql/" + sub + "/validate", manager, Map.of("approve", true), 422).path("code").asText());
        assertEquals("QUALIFIED_SQL", post("/api/sql/" + sub + "/validate", manager, Map.of("approve", true, "confirmIntent", true)).path("status").asText());
    }

    @Test
    void M4_11_smallAccountNeedsReasonAndApproval_andTwentyPercentShare() throws Exception {
        UUID s = prospect("Small Co", "Bengaluru", 120, "CFO");
        UUID a = accountOf(s);
        assertEquals("SMALL_REASON_REQUIRED", post("/api/accounts/" + a + "/sql", bdr1, sqlBody(s, Map.of()), 422).path("code").asText());
        String sub = post("/api/accounts/" + a + "/sql", bdr1, sqlBody(s, Map.of("smallExceptionReason", "Marquee logo")))
                .path("submissionId").asText();
        post("/api/sql/" + sub + "/validate", manager, Map.of("approve", true), 422);
        // first small SQL of the month = 1 of 1 = 100% > 20%: blocked until enough normal SQLs exist
        assertEquals("SMALL_SHARE_EXCEEDED", post("/api/sql/" + sub + "/validate", manager, Map.of("approve", true, "approveSmall", true), 422).path("code").asText());
        for (int i = 0; i < 4; i++) qualifiedAccount("Big " + i, 600, ae1);
        assertEquals("QUALIFIED_SQL", post("/api/sql/" + sub + "/validate", manager, Map.of("approve", true, "approveSmall", true)).path("status").asText());
    }

    @Test
    void M4_5_rejectionNeedsReasonAndReturnsToBdr() throws Exception {
        UUID s = prospect("Reject Co", "Bengaluru", 620, "CFO");
        UUID a = accountOf(s);
        String sub = post("/api/accounts/" + a + "/sql", bdr1, sqlBody(s, Map.of())).path("submissionId").asText();
        post("/api/sql/" + sub + "/validate", bdr1, Map.of("approve", false), 403);
        assertEquals("REJECTION_REASON_REQUIRED", post("/api/sql/" + sub + "/validate", manager, Map.of("approve", false), 422).path("code").asText());
        post("/api/sql/" + sub + "/validate", manager, Map.of("approve", false, "rejectionReasonId", pick("SQL_REJECTION_REASON", "BANT"), "note", "Need stronger need"));
        assertEquals("REJECTED_NOT_ICP", db.queryForObject("SELECT status FROM crm.sql_submission WHERE id = ?::uuid", String.class, sub));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND title LIKE 'SQL rejected%'", Integer.class, bdr1));
        assertEquals("PROSPECTING", db.queryForObject("SELECT visibility_zone FROM crm.account WHERE id = ?", String.class, a));
        assertEquals("CANCELLED", db.queryForObject("SELECT status FROM crm.meeting WHERE account_id = ?", String.class, a));
    }

    @Test
    void M4_9_aeCorrectsAfterL1_originalBdrValueKept() throws Exception {
        var q = qualifiedAccount("Correct Co", 600, ae1);
        String sub = q.get("submission").toString();
        post("/api/sql/" + sub + "/corrections", bdr1, Map.of("field", "license_potential", "value", "420"), 403);
        JsonNode c = post("/api/sql/" + sub + "/corrections", ae1, Map.of("field", "license_potential", "value", "420", "note", "HR confirmed"));
        assertEquals("600", c.get(0).path("bdrValue").asText());
        assertEquals("420", c.get(0).path("correctedValue").asText());
        assertEquals(420, db.queryForObject("SELECT license_potential FROM crm.account WHERE id = ?", Integer.class, q.get("account")));
        assertEquals(600, db.queryForObject("SELECT license_potential FROM crm.sql_submission WHERE id = ?::uuid", Integer.class, sub));
        assertEquals("MEDIUM", db.queryForObject("SELECT size_bucket_code FROM crm.account WHERE id = ?", String.class, q.get("account")));
    }

    @Test
    void BRD_4_3_noShowUpdatesSqlStatus() throws Exception {
        var q = qualifiedAccount("Noshow Co", 600, ae1);
        db.update("UPDATE crm.sql_submission SET status = 'MEETING_BOOKED' WHERE id = ?", q.get("submission"));
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "NO_SHOW"));
        assertEquals("NO_SHOW", db.queryForObject("SELECT status FROM crm.sql_submission WHERE id = ?", String.class, q.get("submission")));
    }
}
