package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.shared.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.beans.factory.annotation.Autowired;
import com.prosperr.crm.outreach.application.SequenceService;

/** Someone leaves: their book is spread over the team by load, and nothing is left behind or keeps emailing. */
class HandoverIT extends IntegrationTest {
    @Autowired SequenceService sequences;

    @Test
    void leavingBdrsBookIsBalancedAndEverythingMoves() throws Exception {
        UUID bdr3 = user(Role.BDR, "Third BDR");
        // bdr2 already has two prospects, bdr3 none: a balanced plan should favour bdr3
        account(bdr2, "Busy One", "busy1.com", "Bengaluru", 300);
        account(bdr2, "Busy Two", "busy2.com", "Bengaluru", 300);
        List<UUID> book = new ArrayList<>();
        for (int i = 1; i <= 3; i++) book.add(account(bdr1, "Leaver Co " + i, "leaver" + i + ".com", "Bengaluru", 300));
        UUID person = UUID.fromString(stakeholder(bdr1, book.get(0), "Rita Sharma", "CFO", "rita@leaver1.com", null, null).path("id").asText());
        logCall(bdr1, person, Map.of("outcomeCode", "CALL_LATER", "followUpDate", today().plusDays(2).toString()));
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(person), "startedFromCall", false));

        post("/api/admin/users/" + bdr1 + "/deactivate", admin, null);

        // the leaver's running sequence is paused by the sender, never sent from their mailbox
        int before = mail.sent.size();
        db.update("UPDATE crm.sequence_enrollment SET next_send_at = ?", java.sql.Timestamp.from(clock.instant().minusSeconds(60)));
        sequences.processDue();
        assertEquals(before, mail.sent.size(), "nothing goes out from a deactivated user's mailbox");
        assertEquals("PAUSED", db.queryForObject("SELECT status FROM crm.sequence_enrollment", String.class));

        JsonNode plan = get("/api/assignment/handover/" + bdr1, manager);
        assertEquals(3, plan.path("moves").size());
        Map<String, Integer> per = new HashMap<>();
        plan.path("moves").forEach(m -> per.merge(m.path("toUserId").asText(), 1, Integer::sum));
        assertTrue(per.getOrDefault(bdr3.toString(), 0) >= 2, "least-loaded BDR gets most: " + per);

        // the manager overrides one row, then applies
        List<Map<String, Object>> moves = new ArrayList<>();
        plan.path("moves").forEach(m -> moves.add(Map.of("accountId", m.path("accountId").asText(),
                "toUserId", m.path("accountId").asText().equals(book.get(0).toString()) ? bdr2.toString() : m.path("toUserId").asText())));
        JsonNode res = post("/api/assignment/handover/" + bdr1, manager, Map.of("moves", moves));
        assertEquals(3, res.path("moved").asInt());
        assertEquals(0, res.path("remaining").asInt());

        // ownership, "Mine", contacts, tasks, sequences all moved to bdr2 for the first account
        assertEquals(0, db.queryForObject("SELECT count(*) FROM crm.account_bdr WHERE bdr_id = ?", Integer.class, bdr1));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.account_bdr WHERE bdr_id = ? AND account_id = ?", Integer.class, bdr2, book.get(0)));
        assertEquals(bdr2, db.queryForObject("SELECT bdr_owner_id FROM crm.stakeholder WHERE id = ?", UUID.class, person));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND status = 'OPEN'", Integer.class, bdr1));
        assertEquals(bdr2, db.queryForObject("SELECT enrolled_by FROM crm.sequence_enrollment", UUID.class));
        assertEquals("PAUSED", db.queryForObject("SELECT status FROM crm.sequence_enrollment", String.class));
        assertTrue(db.queryForObject("SELECT count(*) FROM crm.assignment_history WHERE previous_owner_id = ? AND owner_type = 'BDR'",
                Integer.class, bdr1) >= 1, "the move is in the history");

        // a second apply is harmless
        assertEquals(0, post("/api/assignment/handover/" + bdr1, manager, Map.of("moves", moves)).path("moved").asInt());
    }

    @Test
    void leavingAesDealsGoToTheLeastLoadedAe() throws Exception {
        JsonNode plan0 = get("/api/assignment/handover/" + ae1 + "?strategy=ONE&toUserId=" + ae2, manager);
        assertEquals("ONE", plan0.path("strategy").asText());
        post("/api/admin/users/" + ae1 + "/deactivate", admin, null);
        JsonNode plan = get("/api/assignment/handover/" + ae1, manager);
        plan.path("moves").forEach(m -> assertEquals(ae2.toString(), m.path("toUserId").asText(), "only one other AE"));
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/assignment/handover/" + ae1), bdr2, null, 403);
    }
}
