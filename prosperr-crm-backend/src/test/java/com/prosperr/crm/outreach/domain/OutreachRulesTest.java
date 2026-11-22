package com.prosperr.crm.outreach.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.StopReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutreachRulesTest {
    final Map<String, Map<String, String>> snippets = Map.of(
            "CALL_LINE", Map.of("DEFAULT", "I tried reaching you by phone just now."),
            "PERSONA_PITCH", Map.of("FINANCE", "As a finance leader: cost-neutral.", "HR", "For your people: 5-8% more.",
                    "PAYROLL", "For payroll: no extra processing."),
            "CUSTOMER_PROOF", Map.of("DEFAULT", "Live with Livspace."),
            "SIGNATURE", Map.of("DEFAULT", "{{Sender Full Name}} / Prosperr.io | {{Sender Phone}}"));
    final TemplateRenderer r = new TemplateRenderer(snippets);
    final String body = "Hi {{First Name}},\n\n{{Call Line}}\n\nYou lead {{Company}}. As {{Designation}} you will like this.\n\n{{Persona Pitch}}\n\n{{Customer Proof}}\n\nMore details here: {{Product Link}}\n\nBest,\n{{Signature}}";

    MergeContext ctx(String first, String designation, String link, Department d, boolean fromCall) {
        return new MergeContext(first, "ABC Tech", designation, "Jasim", "Jasim Khan", "+91 98765 43210", link,
                null, null, d, fromCall);
    }

    @Test
    void M3_4_mergeFieldsAndSignature() {
        var out = r.render("Prosperr <> {{Company}}", body, ctx("Rita", "CFO", "prosperr.io/fbp", Department.FINANCE, true));
        assertEquals("Prosperr <> ABC Tech", out.subject());
        assertTrue(out.body().startsWith("Hi Rita,"));
        assertTrue(out.body().contains("I tried reaching you by phone"));
        assertTrue(out.body().contains("Jasim Khan / Prosperr.io | +91 98765 43210"));
        assertFalse(out.body().contains("{{"));
    }

    @Test
    void M3_4_emptyFieldRules() {
        var out = r.render("s", body, ctx(null, null, null, Department.FINANCE, false));
        assertTrue(out.body().startsWith("Hi there,"));
        assertFalse(out.body().contains("More details here"), "Product Link line dropped");
        assertFalse(out.body().contains("you will like this"), "Designation sentence dropped");
        assertTrue(out.body().contains("You lead ABC Tech."), "rest of that line kept");
        assertFalse(out.body().contains("tried reaching you"), "Call Line only after a missed call (M3-7)");
    }

    @Test
    void M3_4_sendingBlockedWhenRequiredFieldEmpty() {
        var noCompany = new MergeContext("Rita", " ", "CFO", "Jasim", "Jasim Khan", "+91", null, null, null,
                Department.FINANCE, false);
        assertThrows(DomainException.class, () -> r.render("s", body, noCompany));
        var noPhone = new MergeContext("Rita", "ABC", "CFO", "Jasim", "Jasim Khan", null, null, null, null,
                Department.FINANCE, false);
        assertThrows(DomainException.class, () -> r.render("s", body, noPhone));
    }

    @Test
    void M3_5_personaPitchByDepartment() {
        assertTrue(r.render("s", body, ctx("A", "CHRO", null, Department.HR, false)).body().contains("For your people"));
        assertTrue(r.render("s", body, ctx("A", "x", null, Department.PAYROLL, false)).body().contains("For payroll"));
        assertTrue(r.render("s", body, ctx("A", "x", null, Department.FINANCE, false)).body().contains("finance leader"));
    }

    @Test
    void M3_9_sendWindowWeekdays0930to1830Ist() {
        SendWindow w = SendWindow.defaultIst();
        // Fri 20 Nov 2026 10:00 IST = 04:30Z
        assertTrue(w.isOpen(Instant.parse("2026-11-20T04:30:00Z")));
        // Fri 19:00 IST -> next Monday 09:30 IST (04:00Z)
        assertEquals(Instant.parse("2026-11-23T04:00:00Z"), w.nextOpen(Instant.parse("2026-11-20T13:30:00Z")));
        // Mon 08:00 IST -> same day 09:30 IST
        assertEquals(Instant.parse("2026-11-23T04:00:00Z"), w.nextOpen(Instant.parse("2026-11-23T02:30:00Z")));
    }

    @Test
    void M3_3_stepDaysFromSequenceStart() {
        SendWindow w = SendWindow.defaultIst();
        Instant start = Instant.parse("2026-11-16T05:00:00Z"); // Mon 10:30 IST
        assertEquals(Instant.parse("2026-11-18T05:00:00Z"), w.stepTime(start, 2), "Followup1 day 2 = Wed");
        assertEquals(Instant.parse("2026-11-23T04:00:00Z"), w.stepTime(start, 5), "day 5 = Sat -> Mon 09:30");
    }

    @Test
    void M3_2_and_M3_6_enrollAndStopRules() {
        SequenceRules s = new SequenceRules();
        assertThrows(DomainException.class, () -> s.checkEnroll(false, false, false));
        assertThrows(DomainException.class, () -> s.checkEnroll(true, true, false));
        assertThrows(DomainException.class, () -> s.checkEnroll(true, false, true));
        s.checkEnroll(true, false, false);
        assertEquals(StopReason.REPLY, s.stopReason(new SequenceRules.ThreadState(true, false, false, false, false)).orElseThrow());
        assertEquals(StopReason.BOUNCE, s.stopReason(new SequenceRules.ThreadState(false, true, false, false, false)).orElseThrow());
        assertEquals(StopReason.OPT_OUT, s.stopReason(new SequenceRules.ThreadState(false, false, false, false, true)).orElseThrow());
        assertEquals(StopReason.L1_BOOKED, s.stopReason(new SequenceRules.ThreadState(false, false, false, true, false)).orElseThrow());
        assertTrue(s.stopReason(new SequenceRules.ThreadState(false, false, false, false, false)).isEmpty());
        assertFalse(s.underDailyCap(80, 80));
    }
}
