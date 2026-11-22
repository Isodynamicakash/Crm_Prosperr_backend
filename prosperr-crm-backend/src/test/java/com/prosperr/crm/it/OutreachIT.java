package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.outreach.application.SequenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M3 email sequences. Uses FakeMailGateway instead of Gmail. */
class OutreachIT extends IntegrationTest {
    @Autowired SequenceService sequences;

    UUID acc, cfo, chro;

    void setup() throws Exception {
        acc = account(bdr1, "Mail Co", "mail.co", "Bengaluru", 600);
        cfo = UUID.fromString(stakeholder(bdr1, acc, "Rita Sharma", "CFO", "rita@mail.co", null, null).path("id").asText());
        chro = UUID.fromString(stakeholder(bdr1, acc, "Asha Rao", "CHRO", "asha@mail.co", null, null).path("id").asText());
    }

    @Test
    void M3_1_needsConnectedMailbox() throws Exception {
        setup();
        assertEquals("MAILBOX_NOT_CONNECTED", post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo)), 422).path("code").asText());
    }

    @Test
    void M3_3_M3_5_M3_7_M3_8_introFromBdrMailbox_personaByDepartment_loggedOnTimeline_followupInThread() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        JsonNode r = post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo), "startedFromCall", true));
        assertEquals("ENROLLED", r.get(0).path("result").asText());
        assertEquals(1, sequences.processDue());
        var intro = mail.sent.get(0);
        assertEquals("jasim@prosperr.io", intro.fromAddress());
        assertEquals("rita@mail.co", intro.to());
        assertTrue(intro.subject().contains("Mail Co"));
        assertTrue(intro.body().contains("finance leader"), "Finance persona pitch");
        assertTrue(intro.body().contains("tried reaching you by phone"), "Call Line after Did Not Pick");
        assertTrue(intro.body().contains("/public/unsubscribe/"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.activity WHERE type = 'EMAIL' AND stakeholder_id = ?", Integer.class, cfo));
        // Followup1 goes out on day 2 (or the next weekday if day 2 is a weekend), in the same thread
        for (int i = 0; i < 5 && mail.sent.size() < 2; i++) { clock.advance(Duration.ofDays(1)); sequences.processDue(); }
        assertEquals(2, mail.sent.size());
        assertEquals(intro.threadId() == null ? "thread-1" : intro.threadId(), mail.sent.get(1).threadId());
        assertEquals("Following up on my previous email", mail.sent.get(1).subject());
    }

    @Test
    void M3_2_skipsDncAndAlreadyRunning_M3_11_warnsSameCompanySameDay() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo)));
        JsonNode r = post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo, chro)));
        assertEquals("SKIPPED", r.get(0).path("result").asText());
        assertEquals("ENROLLED", r.get(1).path("result").asText());
        assertTrue(r.get(1).path("warning").asText().contains("same day"));
    }

    @Test
    void M3_6_replyStopsSequence_setsConnected_createsCallTask() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo)));
        sequences.processDue();
        mail.repliedThreads.add(mail.sent.get(0).threadId() == null ? "thread-1" : mail.sent.get(0).threadId());
        assertEquals(1, sequences.pollReplies());
        assertEquals("REPLY", db.queryForObject("SELECT stop_reason FROM crm.sequence_enrollment WHERE stakeholder_id = ?", String.class, cfo));
        assertEquals("CONNECTED", db.queryForObject("SELECT engagement_status FROM crm.stakeholder WHERE id = ?", String.class, cfo));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE stakeholder_id = ? AND type = 'CALL' AND status = 'OPEN'", Integer.class, cfo));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE type = 'EMAIL_REPLY'", Integer.class));
    }

    @Test
    void M3_6_l1BookedStopsSequencesAtTheAccount() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(chro)));
        logCall(bdr1, cfo, Map.of("outcomeCode", "L1_BOOKED"));
        post("/api/accounts/" + acc + "/sql", bdr1, sqlBody(cfo, Map.of()));
        assertEquals("L1_BOOKED", db.queryForObject("SELECT stop_reason FROM crm.sequence_enrollment WHERE stakeholder_id = ?", String.class, chro));
    }

    @Test
    void M3_3_reminderToBdrWithCallTaskAtTheEnd() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo)));
        for (int i = 0; i < 14; i++) { sequences.processDue(); clock.advance(Duration.ofDays(1)); }
        assertEquals(4, mail.sent.size());
        assertEquals("jasim@prosperr.io", mail.sent.get(3).to(), "internal reminder goes to the BDR");
        assertEquals("COMPLETED", db.queryForObject("SELECT status FROM crm.sequence_enrollment WHERE stakeholder_id = ?", String.class, cfo));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE stakeholder_id = ? AND source = 'SEQUENCE'", Integer.class, cfo));
    }

    @Test
    void M3_9_noSendOutsideWindow_M3_10_monitorShowsFailures() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo)));
        clock.advance(Duration.ofHours(10)); // 20:30 IST
        assertEquals(0, sequences.processDue());
        assertTrue(mail.sent.isEmpty());
        mail.fail = true;
        clock.advance(Duration.ofHours(14)); // next day 10:30 IST; skip weekends
        for (int i = 0; i < 3 && db.queryForObject("SELECT count(*) FROM crm.email_message WHERE status = 'FAILED'", Integer.class) == 0; i++) {
            sequences.processDue();
            clock.advance(Duration.ofDays(1));
        }
        JsonNode mon = get("/api/sequences/monitor", bdr1);
        assertEquals(1, mon.path("failedLast7Days").size());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE type = 'SEQUENCE_SEND_FAILED'", Integer.class));
    }

    @Test
    void Privacy_unsubscribeLinkSetsDoNotContact() throws Exception {
        setup();
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));
        String id = post("/api/sequences/start", bdr1, Map.of("stakeholderIds", List.of(cfo))).get(0).path("enrollmentId").asText();
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/public/unsubscribe/" + id)).andReturn();
        assertEquals(200, res.getResponse().getStatus());
        assertTrue(db.queryForObject("SELECT do_not_contact FROM crm.stakeholder WHERE id = ?", Boolean.class, cfo));
        assertEquals("OPT_OUT", db.queryForObject("SELECT stop_reason FROM crm.sequence_enrollment WHERE id = ?::uuid", String.class, id));
    }
}
