package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.config.CrmProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FormFillServiceTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-23T04:30:00Z"), ZoneOffset.UTC);
    final AtomicReference<String> reply = new AtomicReference<>("{}");
    final AtomicReference<String> sent = new AtomicReference<>();
    final ObjectMapper json = new ObjectMapper();
    final SarvamClient fake = new SarvamClient(json, new CrmProperties(null, null, null, new CrmProperties.Ai("k", null, null, null))) {
        @Override public String chat(String system, String user) { sent.set(user); return reply.get(); }
    };
    final FormFillService service = new FormFillService(fake, new CallNoteService(fake, null, json, clock), null, json, clock);

    @Test
    void stakeholder_contactDetailsReadLocallyAndNeverSentToAi() {
        reply.set("{\"fullName\":\"Priya Raghavan\",\"designation\":\"VP HR\",\"department\":\"hr\"}");
        var out = service.fromText(UUID.randomUUID(), FormFillService.Form.STAKEHOLDER,
                "Priya Raghavan, VP HR\nM: +91 98450 12345 | Priya.R@Infosys.com\nlinkedin.com/in/priya-r");
        assertThat(out.get("emails")).isEqualTo(List.of("priya.r@infosys.com"));
        assertThat(out.get("phones")).isEqualTo(List.of("+919845012345"));
        assertThat(out.get("linkedinUrl")).isEqualTo("https://linkedin.com/in/priya-r");
        assertThat(out.get("department")).isEqualTo("HR");
        assertThat(sent.get()).doesNotContain("98450").doesNotContain("Infosys.com");
    }

    @Test
    void meeting_unknownOutcomeAndPastDateDropped() {
        reply.set("{\"outcome\":\"WON_THE_DEAL\",\"notes\":\"ok\",\"nextStepDue\":\"2026-11-01\"}");
        var out = service.fromText(UUID.randomUUID(), FormFillService.Form.MEETING, "note");
        assertThat(out.get("outcome")).isNull();
        assertThat(out.get("nextStepDue")).isNull();
        assertThat(sent.get()).contains("Mon 2026-11-30");   // calendar so weekdays resolve correctly
    }

    @Test
    void helpers() {
        assertThat(FormFillService.oneOf(" both ", List.of("FBP", "PAYROLL", "BOTH"))).isEqualTo("BOTH");
        assertThat(FormFillService.oneOf("maybe", List.of("FBP"))).isNull();
        LocalDate today = LocalDate.of(2026, 11, 23);
        assertThat(FormFillService.futureDate("2026-11-24", today)).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(FormFillService.futureDate("next week", today)).isNull();
    }
}
