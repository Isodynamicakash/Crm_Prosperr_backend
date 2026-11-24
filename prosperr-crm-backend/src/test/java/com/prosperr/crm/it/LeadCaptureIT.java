package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Event QR: a prospect fills the form on their phone and lands with the BDR whose QR it was, never twice. */
class LeadCaptureIT extends IntegrationTest {

    private JsonNode submit(String token, Map<String, Object> body, String ip, int status) throws Exception {
        return call(MockMvcRequestBuilders.post("/public/qr/" + token).header("X-Forwarded-For", ip), null, body, status);
    }

    private Map<String, Object> form(String name, String company, String email, String phone) {
        Map<String, Object> f = new HashMap<>();
        f.put("fullName", name); f.put("company", company); f.put("designation", "Head of HR");
        f.put("email", email); f.put("phone", phone); f.put("consent", true);
        return f;
    }

    @Test
    void scanningCreatesAContactForTheBdrWithATaskAndNoDuplicates() throws Exception {
        UUID qrId = UUID.fromString(post("/api/lead-qr", bdr1, Map.of("eventName", "HR Summit Mumbai", "sendWelcome", true)).path("id").asText());
        String token = db.queryForObject("SELECT token FROM crm.lead_qr WHERE id = ?", String.class, qrId);
        put("/api/mailbox", bdr1, Map.of("gmailAddress", "jasim@prosperr.io"));

        // what the prospect sees: the event and the rep's first name only
        JsonNode d = call(MockMvcRequestBuilders.get("/public/qr/" + token), null, null, 200);
        assertEquals("HR Summit Mumbai", d.path("eventName").asText());
        assertEquals("Jasim", d.path("repName").asText());

        submit(token, form("Neha Kapoor", "Acme Foods", "neha.kapoor@acmefoods.in", "98765 43210"), "10.0.0.1", 200);
        submit(token, form("Neha Kapoor", "Acme Foods", "neha.kapoor@acmefoods.in", "98765 43210"), "10.0.0.1", 200);   // double tap

        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.lead_capture", Integer.class));
        Map<String, Object> acc = db.queryForMap("""
                SELECT a.name, a.domain, pv.code AS source FROM crm.account a JOIN crm.picklist_value pv ON pv.id = a.lead_source_id""");
        assertEquals("Acme Foods", acc.get("name"));
        assertEquals("acmefoods.in", acc.get("domain"), "the work email gives the company domain");
        assertEquals("EVENT", acc.get("source"));
        assertEquals(bdr1, db.queryForObject("SELECT bdr_owner_id FROM crm.stakeholder", UUID.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.account_bdr WHERE bdr_id = ?", Integer.class, bdr1), "in the BDR's Mine list");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND type = 'FOLLOW_UP' AND title LIKE '%HR Summit%'", Integer.class, bdr1));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.notification WHERE user_id = ? AND type = 'LEAD_CAPTURED'", Integer.class, bdr1));
        assertEquals(1, mail.sent.size(), "the welcome email went from the BDR's mailbox");
        assertEquals("neha.kapoor@acmefoods.in", mail.sent.get(0).to());
        assertEquals("jasim@prosperr.io", mail.sent.get(0).fromAddress());

        // someone already in the CRM (another BDR's contact) is matched, not duplicated, and nothing is sent
        UUID other = account(bdr2, "Globex", "globex.com", "Bengaluru", 300);
        stakeholder(bdr2, other, "Ravi Iyer", "CFO", "ravi@globex.com", null, null);
        submit(token, form("Ravi Iyer", "Globex India", "RAVI@globex.com", null), "10.0.0.2", 200);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.stakeholder WHERE first_name = 'Ravi'", Integer.class));
        assertEquals("EXISTING", db.queryForObject("SELECT outcome FROM crm.lead_capture WHERE email = 'ravi@globex.com'", String.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND title LIKE 'Follow up with Ravi%'", Integer.class, bdr2),
                "the follow-up goes to the BDR who owns the contact");
        assertEquals(1, mail.sent.size());

        JsonNode list = get("/api/lead-qr", bdr1);
        assertEquals(2, list.get(0).path("captured").asInt());
        assertEquals(2, get("/api/lead-qr/" + qrId + "/captures", bdr1).size());
        call(MockMvcRequestBuilders.get("/api/lead-qr/" + qrId + "/captures"), bdr2, null, 404);   // not your QR
    }

    @Test
    void theFormRejectsBadInputBotsFloodsAndOldQrs() throws Exception {
        UUID qrId = UUID.fromString(post("/api/lead-qr", bdr1, Map.of("eventName", "Payroll Meetup", "sendWelcome", false)).path("id").asText());
        String token = db.queryForObject("SELECT token FROM crm.lead_qr WHERE id = ?", String.class, qrId);

        Map<String, Object> noConsent = form("A Person", "Initech", "a@initech.com", null);
        noConsent.put("consent", false);
        assertEquals("CONSENT_REQUIRED", submit(token, noConsent, "10.1.0.1", 422).path("code").asText());
        submit(token, form("A Person", "Initech", "not-an-email", null), "10.1.0.1", 422);

        Map<String, Object> bot = form("Bot", "Spam Co", "bot@spam.com", null);
        bot.put("website", "http://spam.example");                     // the hidden field only bots fill
        submit(token, bot, "10.1.0.2", 200);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM crm.lead_capture", Integer.class));

        for (int i = 0; i < 6; i++) submit(token, form("Person " + i, "Co " + i, "p" + i + "@co" + i + ".com", null), "10.1.0.3", 200);
        assertEquals("TOO_MANY", submit(token, form("Person 7", "Co 7", "p7@co7.com", null), "10.1.0.3", 422).path("code").asText());

        call(MockMvcRequestBuilders.patch("/api/lead-qr/" + qrId), bdr1, Map.of("active", false), 204);
        call(MockMvcRequestBuilders.get("/public/qr/" + token), null, null, 404);
        call(MockMvcRequestBuilders.get("/public/qr/nosuchcode"), null, null, 404);
    }
}
