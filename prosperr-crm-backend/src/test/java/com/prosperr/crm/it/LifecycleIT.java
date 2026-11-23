package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.lifecycle.application.LifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M8 (active, inactive, revival) and M7-5. */
class LifecycleIT extends IntegrationTest {
    @Autowired LifecycleService lifecycle;

    @Test
    void M8_1_flaggedAfter60DaysWithoutActivity_thenKeepActiveNeedsNextStep() throws Exception {
        var q = qualifiedAccount("Stale Co", 600, ae1);
        UUID acc = q.get("account");
        db.update("UPDATE crm.account SET created_at = now() - interval '200 days', last_activity_at = now() - interval '61 days' WHERE id = ?", acc);
        clock.set(java.time.Instant.now());
        assertEquals(1, lifecycle.flagStaleAccounts());
        assertEquals(1, get("/api/lifecycle/to-be-moved", ae1).size());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE user_id = ? AND type = 'ACCOUNT_TO_BE_MOVED'", Integer.class, ae1));
        assertEquals("NEXT_STEP_REQUIRED", post("/api/accounts/" + acc + "/keep-active", ae1, Map.of("nextStep", "Call CFO"), 422).path("code").asText());
        post("/api/accounts/" + acc + "/keep-active", ae1, Map.of("nextStep", "Call CFO", "nextStepDue", today().plusDays(3).toString()));
        assertEquals(0, get("/api/lifecycle/to-be-moved", ae1).size());
    }

    @Test
    void M8_2_M8_4_M8_5_moveToInactiveNeedsReasonRemarkRevisit_keepsStageAndHistory() throws Exception {
        var q = qualifiedAccount("Hold Co", 600, ae1);
        UUID acc = q.get("account");
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        Map<String, Object> m = new HashMap<>();
        m.put("reasonId", pick("INACTIVE_REASON", "ON_HOLD"));
        assertEquals("REMARK_REQUIRED", post("/api/accounts/" + acc + "/inactive", ae1, m, 422).path("code").asText());
        m.put("remark", "Budget freeze till April");
        assertEquals("REVISIT_REQUIRED", post("/api/accounts/" + acc + "/inactive", ae1, m, 422).path("code").asText());
        post("/api/accounts/" + acc + "/inactive", bdr1, Map.of("reasonId", pick("INACTIVE_REASON", "BUDGET"), "remark", "x"), 403);
        m.put("revisitOn", today().plusDays(90).toString());
        post("/api/accounts/" + acc + "/inactive", ae1, m);
        assertEquals("INACTIVE", db.queryForObject("SELECT account_status FROM crm.account WHERE id = ?", String.class, acc));
        assertEquals("L1", stage(acc), "inactive keeps the stage it stalled at");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.stakeholder WHERE account_id = ?", Integer.class, acc));
        assertEquals(1, get("/api/accounts?status=INACTIVE", manager).path("total").asInt());
        assertEquals(0, get("/api/accounts", manager).path("total").asInt(), "default view is Active");
    }

    @Test
    void M8_5_M8_6_M8_7_revisitDateFeedsRevivalQueue_bdrAddsStakeholder_reactivatesToL1() throws Exception {
        var q = qualifiedAccount("Revive Co", 600, ae1);
        UUID acc = q.get("account");
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        post("/api/accounts/" + acc + "/inactive", ae1, Map.of("reasonId", pick("INACTIVE_REASON", "SPOC_UNRESPONSIVE"),
                "remark", "CFO silent", "revisitOn", today().plusDays(30).toString()));
        clock.advance(Duration.ofDays(31));
        db.update("UPDATE crm.account SET revisit_on = current_date WHERE id = ?", acc);
        assertEquals(1, lifecycle.openRevisits());
        assertEquals(1, get("/api/lifecycle/revival-queue", bdr1).size());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND type = 'REVIVAL'", Integer.class, bdr1));
        JsonNode chro = stakeholder(bdr2, acc, "New CHRO", "CHRO", "chro@revive.co", null, null);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.account", Integer.class), "no duplicate account");
        post("/api/accounts/" + acc + "/reactivate", ae1, Map.of("note", "x"), 403);
        JsonNode r = post("/api/accounts/" + acc + "/reactivate", bdr2, Map.of("reasonId", pick("REACTIVATION_REASON", "NEW_STAKEHOLDER"),
                "newStakeholderId", chro.path("id").asText()));
        assertEquals("L1", r.path("stage").asText());
        assertEquals("ACTIVE", db.queryForObject("SELECT account_status FROM crm.account WHERE id = ?", String.class, acc));
        assertEquals(2, get("/api/accounts/" + acc + "/status-history", manager).size());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE type = 'ACCOUNT_REACTIVATED' AND user_id = ?", Integer.class, ae1));
    }

    @Test
    void M7_5_oneUnresponsiveSpocNeverMakesAccountInactive_promptsMultithreading() throws Exception {
        var q = qualifiedAccount("Thread Co", 600, ae1);
        UUID acc = q.get("account");
        patch("/api/stakeholders/" + q.get("stakeholder"), ae1, Map.of("primarySpoc", true));
        patch("/api/stakeholders/" + q.get("stakeholder") + "/engagement", ae1, Map.of("status", "UNRESPONSIVE"));
        assertEquals("ACTIVE", db.queryForObject("SELECT account_status FROM crm.account WHERE id = ?", String.class, acc));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE user_id = ? AND type = 'SPOC_UNRESPONSIVE'", Integer.class, ae1));
        stakeholder(ae1, acc, "Asha HR", "CHRO", null, null, null);
        JsonNode mt = get("/api/accounts/" + acc + "/multithreading", ae1);
        assertTrue(mt.path("needsMultithreading").asBoolean());
        assertEquals("Asha HR", mt.path("suggestedNext").path("name").asText());
        assertEquals(1, mt.path("stakeholdersByFunction").path("HR").size());
        JsonNode tl = get("/api/accounts/" + acc + "/timeline", ae1);
        assertTrue(tl.path("events").size() > 0);
    }

    @Test
    void M8_3_optionalManagerApprovalForInactiveMoves() throws Exception {
        db.update("UPDATE crm.app_setting SET value = 'true' WHERE key = 'require_inactive_approval'");
        var q = qualifiedAccount("Approve Co", 600, ae1);
        JsonNode r = post("/api/accounts/" + q.get("account") + "/inactive", ae1, Map.of("reasonId", pick("INACTIVE_REASON", "BUDGET"), "remark", "No budget"));
        assertEquals("PENDING_APPROVAL", r.path("status").asText());
        assertEquals("ACTIVE", db.queryForObject("SELECT account_status FROM crm.account WHERE id = ?", String.class, q.get("account")));
        assertEquals("INACTIVE", post("/api/accounts/" + q.get("account") + "/inactive", manager,
                Map.of("reasonId", pick("INACTIVE_REASON", "BUDGET"), "remark", "No budget")).path("status").asText());
    }

    @Test
    void Inactive_cancelsUpcomingMeetings() throws Exception {
        var q = qualifiedAccount("Parked Co", 600, ae1);
        post("/api/accounts/" + q.get("account") + "/inactive", ae1, Map.of("reasonId", pick("INACTIVE_REASON", "SPOC_UNRESPONSIVE"),
                "remark", "No response", "revisitOn", today().plusDays(30).toString()));
        assertEquals("CANCELLED", db.queryForObject("SELECT status FROM crm.meeting WHERE id = ?", String.class, q.get("meeting")));
    }
}
