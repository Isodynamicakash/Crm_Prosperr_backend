package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.config.CrmProperties;
import com.prosperr.crm.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallNoteServiceTest {
    // Monday 23 Nov 2026, 10:00 IST
    final Clock clock = Clock.fixed(Instant.parse("2026-11-23T04:30:00Z"), ZoneOffset.UTC);
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<String> sentPrompt = new AtomicReference<>();
    final ObjectMapper json = new ObjectMapper();

    final SarvamClient fake = new SarvamClient(json, new CrmProperties(null, null, null, new CrmProperties.Ai("k", null, null, null))) {
        @Override public String chat(String system, String user) { sentPrompt.set(user); return reply.get(); }
        @Override public Transcript transcribeWithLanguage(byte[] wav) { return new Transcript("transcribed", "en-IN"); }
    };

    final CallNoteService service = new CallNoteService(fake, null, json, clock) {
        @Override Map<String, Outcome> outcomes() {
            var m = new LinkedHashMap<String, Outcome>();
            m.put("CALL_LATER", new Outcome("CALL_LATER", "Call Later", true, false));
            m.put("DID_NOT_PICK", new Outcome("DID_NOT_PICK", "Did Not Pick", false, false));
            m.put("NOT_INTERESTED", new Outcome("NOT_INTERESTED", "Not Interested", false, true));
            return m;
        }
    };

    @Test
    void keepsValidSuggestion() {
        reply.set("{\"outcomeCode\":\"CALL_LATER\",\"remark\":\"Busy, call Monday.\",\"followUpDate\":\"2026-11-30\",\"reason\":\"x\"}");
        var s = service.fromText(UUID.randomUUID(), "busy call monday");
        assertThat(s.outcomeCode()).isEqualTo("CALL_LATER");
        assertThat(s.followUpDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(s.reason()).isNull(); // Call Later takes no reason
    }

    @Test
    void dropsUnknownCodeAndPastOrFarDates() {
        reply.set("prefix {\"outcomeCode\":\"MADE_UP\",\"remark\":null,\"followUpDate\":\"2026-11-15\"} trailing");
        var s = service.fromText(UUID.randomUUID(), "note");
        assertThat(s.outcomeCode()).isNull();
        assertThat(s.followUpDate()).isNull();
        assertThat(s.remark()).isEqualTo("note"); // falls back to the note itself
        reply.set("{\"outcomeCode\":\"call_later\",\"followUpDate\":\"2028-01-01\"}");
        assertThat(service.fromText(UUID.randomUUID(), "n").followUpDate()).isNull();
        assertThat(service.fromText(UUID.randomUUID(), "n").outcomeCode()).isEqualTo("CALL_LATER");
    }

    @Test
    void keepsReasonOnlyWhereRequired() {
        reply.set("{\"outcomeCode\":\"NOT_INTERESTED\",\"remark\":\"No.\",\"reason\":\"Using Zaggle\"}");
        assertThat(service.fromText(UUID.randomUUID(), "n").reason()).isEqualTo("Using Zaggle");
    }

    @Test
    void neverSendsPhoneNumbersOrEmails() {
        reply.set("{}");
        service.fromText(UUID.randomUUID(), "Call him on +91 98765 43210 or mail ravi@acme.com");
        assertThat(sentPrompt.get()).doesNotContain("98765").doesNotContain("ravi@acme.com").contains("[number]").contains("[email]");
    }

    @Test
    void unreadableReplyIsAFriendlyError() {
        reply.set(null);
        assertThatThrownBy(() -> service.fromText(UUID.randomUUID(), "n"))
                .isInstanceOf(DomainException.class).hasMessageContaining("by hand");
    }

    @Test
    void rejectsBadInputAndEnforcesHourlyLimit() {
        assertThatThrownBy(() -> service.fromAudio(UUID.randomUUID(), new byte[10])).hasMessageContaining("empty");
        assertThatThrownBy(() -> service.fromAudio(UUID.randomUUID(), new byte[5_000])).hasMessageContaining("WAV");
        assertThatThrownBy(() -> service.fromText(UUID.randomUUID(), "x".repeat(2_001))).hasMessageContaining("2,000");
        reply.set("{}");
        UUID u = UUID.randomUUID();
        for (int i = 0; i < CallNoteService.HOURLY_LIMIT; i++) service.fromText(u, "n");
        assertThatThrownBy(() -> service.fromText(u, "n")).isInstanceOf(DomainException.class).hasMessageContaining("this hour");
    }
}
