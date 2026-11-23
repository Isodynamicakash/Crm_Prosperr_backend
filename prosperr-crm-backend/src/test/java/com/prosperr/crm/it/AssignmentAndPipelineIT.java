package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M5 (assignment, capacity) and M6 (meetings, stages, exit steps, temperature, Won/Lost). */
class AssignmentAndPipelineIT extends IntegrationTest {

    @Test
    void M5_1_M5_3_submittedSqlWaitsInManagerQueue_neverAutoAssigned() throws Exception {
        UUID a = account(bdr1, "Queue Co", "queue.co", "Bengaluru", 600);
        UUID s = UUID.fromString(stakeholder(bdr1, a, "Rita Q", "CFO", null, null, null).path("id").asText());
        logCall(bdr1, s, Map.of("outcomeCode", "L1_BOOKED"));
        post("/api/accounts/" + a + "/sql", bdr1, sqlBody(s, Map.of()));
        JsonNode queue = get("/api/assignment/queue", manager);
        assertEquals(1, queue.size());
        assertNull(db.queryForObject("SELECT ae_owner_id FROM crm.account WHERE id = ?", UUID.class, a), "no automatic assignment");
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/assignment/queue"), bdr1, null, 403);
        post("/api/accounts/" + a + "/assign", bdr1, Map.of("aeId", ae1), 403);
    }

    @Test
    void M5_2_capacityRecommendsLeastLoadedAe_BRD_7_1_metrics() throws Exception {
        qualifiedAccount("Load A", 600, ae1);
        qualifiedAccount("Load B", 600, ae1);
        JsonNode cap = get("/api/assignment/capacity", manager);
        assertEquals(ae2.toString(), cap.path("recommended").path("aeId").asText());
        JsonNode mahesh = null;
        for (JsonNode r : cap.path("aes")) if (r.path("load").path("aeId").asText().equals(ae1.toString())) mahesh = r;
        assertEquals(2, mahesh.path("load").path("activeAccounts").asInt());
        assertEquals(2, mahesh.path("load").path("l1Pending").asInt());
        // an AE sees only their own row
        assertEquals(1, get("/api/assignment/capacity", ae2).path("aes").size());
    }

    @Test
    void M5_4_reassignmentNeedsReason_historyKept_openTasksMove() throws Exception {
        var q = qualifiedAccount("Move Co", 600, ae1);
        post("/api/tasks", manager, Map.of("accountId", q.get("account"), "title", "Prep deck", "dueAt", inDays(1).toString(), "assigneeId", ae1));
        assertEquals("REASSIGN_REASON_REQUIRED", post("/api/accounts/" + q.get("account") + "/assign", manager, Map.of("aeId", ae2), 422).path("code").asText());
        post("/api/accounts/" + q.get("account") + "/assign", manager, Map.of("aeId", ae2, "reasonId", pick("REASSIGNMENT_REASON", "BANDWIDTH")));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND status = 'OPEN'", Integer.class, ae1));
        JsonNode h = get("/api/accounts/" + q.get("account") + "/assignment-history", manager);
        assertEquals(2, h.size());
        assertEquals("AE bandwidth", h.get(1).path("reason").asText());
        assertEquals(ae2, db.queryForObject("SELECT ae_id FROM crm.meeting WHERE id = ?", UUID.class, q.get("meeting")));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE user_id = ? AND type = 'ACCOUNT_REASSIGNED'", Integer.class, ae1));
    }

    @Test
    void M6_3_M6_4_l1CompletedMovesStageToL1_onlyOwnerAeRecordsOutcome() throws Exception {
        var q = qualifiedAccount("Stage Co", 600, ae1);
        // a future meeting can't have an outcome yet
        postOutcomeAsScheduled("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"), 422);
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae2, Map.of("outcome", "COMPLETED_QUALIFIED"), 403);
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED", "notes", "Good demo",
                "nextStep", "Send docs", "nextStepDue", today().plusDays(2).toString()));
        assertEquals("L1", stage(q.get("account")));
        assertEquals("MEETING_COMPLETED", db.queryForObject("SELECT engagement_status FROM crm.stakeholder WHERE id = ?", String.class, q.get("stakeholder")));
        assertEquals("Send docs", db.queryForObject("SELECT next_step FROM crm.account WHERE id = ?", String.class, q.get("account")));
        assertEquals("Send docs", db.queryForObject("SELECT next_step FROM crm.opportunity WHERE id = ?", String.class, q.get("opportunity")));
    }

    @Test
    void M6_10_L2RequiresDocumentsSharedWithAttachment_then_L3RequiresCommercialsSent() throws Exception {
        var q = qualifiedAccount("Exit Co", 600, ae1);
        UUID acc = q.get("account"), opp = q.get("opportunity");
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        String l2 = post("/api/accounts/" + acc + "/meetings", ae1, Map.of("level", "L2", "scheduledStart", inDays(5).toString())).path("id").asText();
        post("/api/meetings/" + l2 + "/outcome", ae1, Map.of("outcome", "COMPLETED_FOLLOW_UP"));
        assertEquals("L1", stage(acc), "L1 exit step not logged yet");
        UUID docs = checklistItem("L1", "DOCS_SHARED");
        assertEquals("ATTACHMENT_REQUIRED", call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/opportunities/" + opp + "/checklist/" + docs), ae1, Map.of("done", true), 422).path("code").asText());
        String att = post("/api/accounts/" + acc + "/attachments", ae1, Map.of("opportunityId", opp, "kind", "DOCUMENTS_SHARED",
                "fileName", "deck.pdf", "externalUrl", "https://drive.example/deck")).path("id").asText();
        put("/api/opportunities/" + opp + "/checklist/" + docs, ae1, Map.of("done", true, "attachmentId", att));
        assertEquals("L2", stage(acc));
        String l3 = post("/api/accounts/" + acc + "/meetings", ae1, Map.of("level", "L3", "scheduledStart", inDays(9).toString())).path("id").asText();
        post("/api/meetings/" + l3 + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        assertEquals("L2", stage(acc), "commercials not sent");
        String com = post("/api/accounts/" + acc + "/attachments", ae1, Map.of("opportunityId", opp, "kind", "COMMERCIAL",
                "fileName", "quote.pdf", "storagePath", "crm/quote.pdf")).path("id").asText();
        put("/api/opportunities/" + opp + "/checklist/" + checklistItem("L2", "COMMERCIALS_SENT"), ae1, Map.of("done", true, "attachmentId", com));
        assertEquals("L3", stage(acc));
        List<String> history = db.queryForList("SELECT to_stage FROM crm.stage_history WHERE opportunity_id = ? ORDER BY changed_at, id", String.class, opp);
        assertEquals(List.of("SQL", "L1", "L2", "L3"), history);
        assertEquals(ae1, db.queryForObject("SELECT changed_by FROM crm.stage_history WHERE opportunity_id = ? AND to_stage = 'L3'", UUID.class, opp));
    }

    @Test
    void M6_11_combinedL1L2MeetingCompletesBothStagesCountedOnce() throws Exception {
        var q = qualifiedAccount("Combo Co", 600, ae1);
        UUID acc = q.get("account"), opp = q.get("opportunity");
        db.update("UPDATE crm.meeting SET also_completes_l2 = true WHERE id = ?", q.get("meeting"));
        String att = post("/api/accounts/" + acc + "/attachments", ae1, Map.of("opportunityId", opp, "kind", "DOCUMENTS_SHARED",
                "fileName", "docs", "externalUrl", "https://x")).path("id").asText();
        put("/api/opportunities/" + opp + "/checklist/" + checklistItem("L1", "DOCS_SHARED"), ae1, Map.of("done", true, "attachmentId", att));
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "COMPLETED_QUALIFIED"));
        assertEquals("L2", stage(acc));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.stage_history WHERE opportunity_id = ? AND to_stage = 'L1'", Integer.class, opp));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.stage_history WHERE opportunity_id = ? AND to_stage = 'L2'", Integer.class, opp));
    }

    @Test
    void M6_3_noShowCreatesRescheduleTaskForTheBdr_andRescheduleLinksMeetings() throws Exception {
        var q = qualifiedAccount("Noshow2 Co", 600, ae1);
        post("/api/meetings/" + q.get("meeting") + "/outcome", ae1, Map.of("outcome", "NO_SHOW"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND type = 'RESCHEDULE_MEETING' AND status = 'OPEN'", Integer.class, bdr1));
        JsonNode m = post("/api/meetings/" + q.get("meeting") + "/reschedule", bdr1, Map.of("scheduledStart", inDays(4).toString()));
        assertEquals(q.get("meeting").toString(), db.queryForObject("SELECT rescheduled_from_id::text FROM crm.meeting WHERE id = ?::uuid", String.class, m.path("id").asText()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM crm.task WHERE type = 'RESCHEDULE_MEETING' AND status = 'OPEN'", Integer.class));
    }

    @Test
    void M6_5_M6_6_temperatureHistoryMrr_and_M6_4_wonLostRules() throws Exception {
        var q = qualifiedAccount("Deal Co", 600, ae1);
        UUID opp = q.get("opportunity");
        int v = get("/api/opportunities/" + opp, ae1).path("opportunity").path("version").asInt();
        Map<String, Object> upd = new HashMap<>(Map.of("temperature", "WARM", "expectedMrr", 150000, "licenseCount", 640));
        upd.put("version", v);
        patch("/api/opportunities/" + opp, ae1, upd);
        patch("/api/opportunities/" + opp, ae1, Map.of("temperature", "HOT"));
        assertEquals(2, get("/api/opportunities/" + opp, ae1).path("temperatureHistory").size());
        assertEquals("STAGE_MOVE_NOT_ALLOWED", post("/api/opportunities/" + opp + "/stage", ae1, Map.of("target", "WON"), 422).path("code").asText());
        assertEquals("LOST_REASON_REQUIRED", post("/api/opportunities/" + opp + "/stage", ae1, Map.of("target", "LOST"), 422).path("code").asText());
        post("/api/opportunities/" + opp + "/stage", ae1, Map.of("target", "LOST", "lostReasonId", pick("LOST_REASON", "PRICING")));
        assertEquals("LOST", stage(q.get("account")));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.audit_log WHERE entity_id = ? AND field_name = 'stage'", Integer.class, opp));
    }

    @Test
    void M6_2_meetingWithPassedTimeAndNoOutcomeIsOverdue() throws Exception {
        var q = qualifiedAccount("Late Co", 600, ae1);
        db.update("UPDATE crm.meeting SET scheduled_start = now() - interval '2 hours' WHERE id = ?", q.get("meeting"));
        JsonNode overdue = get("/api/meetings/overdue-outcomes", ae1);
        assertEquals(1, overdue.size());
    }
}
