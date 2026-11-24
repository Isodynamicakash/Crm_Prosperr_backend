package com.prosperr.crm.ai.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The help guide is complete and the assistant's cited sections turn into links. */
class HelpChatTest {
    @Test
    void guideHasEverySectionWithAPage() throws Exception {
        String md = new String(new ClassPathResource("help/bdr-guide.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<HelpChatService.Section> s = HelpChatService.parse(md);
        assertTrue(s.size() >= 25, "sections: " + s.size());
        for (HelpChatService.Section x : s) {
            assertTrue(x.page().startsWith("/"), x.title());
            assertFalse(x.body().isBlank(), x.title());
        }
        assertTrue(s.stream().anyMatch(x -> x.title().equals("Event QR (lead capture at events)") && x.page().equals("/event-qr")));
    }

    @Test
    void sourcesLineBecomesLinksAndIsRemovedFromTheAnswer() throws Exception {
        HelpChatService h = new HelpChatService(new GroqClient("", "m", new com.fasterxml.jackson.databind.ObjectMapper()), null, null);
        HelpChatService.Answer a = h.finish("Open **My Day** and press **Start session**.\n\nSOURCES: Calling session; My Day: the calling list; Made up", "groq");
        assertEquals("Open **My Day** and press **Start session**.", a.answer());
        assertEquals(2, a.sources().size(), "unknown titles are dropped");
        assertEquals("/my-day", a.sources().get(0).page());
    }

    @Test
    void inlineSourcesWrongLinksAndLanguage() throws Exception {
        HelpChatService h = new HelpChatService(new GroqClient("", "m", new com.fasterxml.jackson.databind.ObjectMapper()), null, null);
        HelpChatService.Answer a = h.finish("See [Home](/home) and [Pipeline](/pipeline). SOURCES: Home; Tasks", "sarvam");
        assertEquals("See [Home](/) and **Pipeline**.", a.answer());
        assertEquals(2, a.sources().size());
        assertTrue(HelpChatService.language("why is my sequence paused?").startsWith("English"));
        assertTrue(HelpChatService.language("मेरा sequence pause क्यों हो गया?").startsWith("Hindi"));
        assertEquals("Tamil", HelpChatService.language("SQL எப்படி சமர்ப்பிப்பது?"));
        assertTrue(HelpChatService.language("event qr kaise use karu booth pe?").startsWith("Hinglish"));
        assertEquals("English", HelpChatService.language("how do I import leads?"));
    }

    @Test
    void groqGetsOnlyTheRelevantSections() throws Exception {
        HelpChatService h = new HelpChatService(new GroqClient("", "m", new com.fasterxml.jackson.databind.ObjectMapper()), null, null);
        String g = h.relevant("why is my email sequence paused?");
        assertTrue(g.contains("## Email sequences"));
        assertTrue(g.contains("## Common problems"));
        assertTrue(g.length() < 9000, "much smaller than the whole guide: " + g.length());
        assertTrue(h.relevant("नमस्ते").contains("## Event QR"), "no English words: the whole guide");
    }
}
