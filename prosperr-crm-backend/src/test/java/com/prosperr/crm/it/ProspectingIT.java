package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M2 (BDR prospecting and calling) and the call-outcome reference list. */
class ProspectingIT extends IntegrationTest {

    UUID acc, cfo, chro;

    void setup() throws Exception {
        acc = account(bdr1, "Call Co", "call.co", "Bengaluru", 600);
        cfo = UUID.fromString(stakeholder(bdr1, acc, "Rita Sharma", "CFO", "rita@call.co", "9876500011", null).path("id").asText());
        chro = UUID.fromString(stakeholder(bdr1, acc, "Asha Rao", "CHRO", "asha@call.co", "9876500012", null).path("id").asText());
    }

    @Test
    void M2_1_myDayListsUntouchedStakeholdersInPriorityOrderWithCallLinks() throws Exception {
        setup();
        JsonNode day = get("/api/my-day", bdr1);
        assertEquals("UNTOUCHED", day.get(0).path("section").asText());
        assertEquals("Rita Sharma", day.get(0).path("stakeholderName").asText(), "CFO before CHRO");
        assertEquals("+919876500011", day.get(0).path("phone").asText());
    }

    @Test
    void M2_3_didNotPick_contacted_callTaskIn2WorkingDays_offersSequence() throws Exception {
        setup();
        JsonNode r = logCall(bdr1, cfo, Map.of("outcomeCode", "DID_NOT_PICK"));
        assertEquals("CONTACTED", r.path("plan").path("newStatus").asText());
        assertTrue(r.path("offerSequence").asBoolean(), "M3-7");
        assertNotNull(r.path("taskId").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE stakeholder_id = ? AND status = 'OPEN' AND type = 'CALL'", Integer.class, cfo));
        assertFalse(r.path("nextQueueItem").isNull(), "save and next");
        // the next outcome replaces the open reminder instead of piling up tasks
        logCall(bdr1, cfo, Map.of("outcomeCode", "DID_NOT_PICK"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE stakeholder_id = ? AND status = 'OPEN'", Integer.class, cfo));
    }

    @Test
    void Decision7_sixNoAnswers_unresponsive_30DayRevisit_andSuggestsNextStakeholder() throws Exception {
        setup();
        JsonNode r = null;
        for (int i = 0; i < 6; i++) r = logCall(bdr1, cfo, Map.of("outcomeCode", "DID_NOT_PICK"));
        assertEquals("UNRESPONSIVE", r.path("plan").path("newStatus").asText());
        assertEquals(today().plusDays(30).toString(), r.path("plan").path("revisitOn").asText());
        assertEquals("Asha Rao", r.path("suggestedNextStakeholder").path("name").asText(), "M2-6 next in priority order");
    }

    @Test
    void M2_3_callLaterNeedsDate_planEditableBeforeSave() throws Exception {
        setup();
        // fails without a date:
        post("/api/stakeholders/" + cfo + "/calls", bdr1, Map.of("outcomeCode", "CALL_LATER"), 422);
        assertEquals("FOLLOW_UP_PENDING", logCall(bdr1, cfo, Map.of("outcomeCode", "CALL_LATER",
                "followUpDate", today().plusDays(5).toString())).path("plan").path("newStatus").asText());
        JsonNode preview = get("/api/stakeholders/" + cfo + "/calls/preview?outcome=DID_NOT_PICK", bdr1);
        assertEquals("CALL", preview.path("taskType").asText());
        JsonNode r = logCall(bdr1, cfo, Map.of("outcomeCode", "DID_NOT_PICK", "overrideTaskDue", today().plusDays(9).toString()));
        assertEquals(today().plusDays(9).toString(), r.path("plan").path("taskDue").asText());
    }

    @Test
    void M2_5_referralCreatesLinkedStakeholderWithConnectionType() throws Exception {
        setup();
        Map<String, Object> body = new HashMap<>();
        body.put("outcomeCode", "REFERRED");
        body.put("referral", Map.of("fullName", "Pooja Iyer", "designation", "Payroll Lead", "emails", List.of("pooja@call.co")));
        JsonNode r = logCall(bdr1, cfo, body);
        JsonNode ref = r.path("referredStakeholder");
        assertEquals(cfo.toString(), ref.path("referredById").asText());
        assertEquals("JUNIOR_VIA_SENIOR_REFERRAL", ref.path("connectionType").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.activity WHERE type = 'REFERRAL'", Integer.class));
    }

    @Test
    void Reference_wrongNumberInvalidatesPhone_wrongIcpSetsAccountFit() throws Exception {
        setup();
        String phoneId = db.queryForObject("SELECT id::text FROM crm.stakeholder_phone WHERE stakeholder_id = ?", String.class, cfo);
        logCall(bdr1, cfo, Map.of("outcomeCode", "WRONG_NUMBER", "phoneId", phoneId));
        assertFalse(db.queryForObject("SELECT is_valid FROM crm.stakeholder_phone WHERE id = ?::uuid", Boolean.class, phoneId));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE type = 'FIND_NUMBER'", Integer.class));
        logCall(bdr1, chro, Map.of("outcomeCode", "WRONG_ICP"));
        assertEquals("NO", db.queryForObject("SELECT icp_fit FROM crm.account WHERE id = ?", String.class, acc));
    }

    @Test
    void M2_9_M2_10_twoBdrsWorkOneAccount_creditGoesToBdrWhoseStakeholderBookedL1() throws Exception {
        setup();
        post("/api/accounts", bdr2, Map.of("name", "Call Co"));
        UUID payroll = UUID.fromString(stakeholder(bdr2, acc, "Ravi Payroll", "Head Of Payroll", "ravi@call.co", null, cfo).path("id").asText());
        // bdr2 cannot log calls on bdr1's stakeholder
        post("/api/stakeholders/" + cfo + "/calls", bdr2, Map.of("outcomeCode", "DID_NOT_PICK"), 403);
        logCall(bdr2, payroll, Map.of("outcomeCode", "L1_BOOKED"));
        // CFO (bdr1) referred the payroll head; level L3 qualifies through the referral
        post("/api/accounts/" + acc + "/sql", bdr2, sqlBody(payroll, Map.of()));
        assertEquals(bdr2, db.queryForObject("SELECT sql_credit_bdr_id FROM crm.account WHERE id = ?", UUID.class, acc));
        assertEquals("SOURCER", db.queryForObject("SELECT role FROM crm.account_bdr WHERE account_id = ? AND bdr_id = ?", String.class, acc, bdr2));
        assertEquals("CONTRIBUTOR", db.queryForObject("SELECT role FROM crm.account_bdr WHERE account_id = ? AND bdr_id = ?", String.class, acc, bdr1));
    }

    @Test
    void M2_4_unlimitedActivities_whatsappLinkedinNote() throws Exception {
        setup();
        for (String t : List.of("WHATSAPP", "LINKEDIN", "NOTE", "OFFICE_VISIT"))
            post("/api/activities", bdr1, Map.of("accountId", acc, "stakeholderId", cfo, "type", t, "remarks", "touch"));
        assertEquals(4, db.queryForObject("SELECT count(*) FROM crm.activity WHERE account_id = ?", Integer.class, acc));
        assertNotNull(db.queryForObject("SELECT last_activity_at FROM crm.account WHERE id = ?", java.sql.Timestamp.class, acc));
    }
}
