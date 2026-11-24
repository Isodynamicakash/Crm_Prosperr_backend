package com.prosperr.crm.ai.application;

import com.prosperr.crm.shared.domain.DomainException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Ask Prosperr": answers a BDR's questions about how the CRM works, only from the BDR guide (help/bdr-guide.md).
 * It never sees or changes CRM data. Uses Groq when GROQ_API_KEY is set (fast), else Sarvam.
 */
@Service
public class HelpChatService {
    static final int MAX_QUESTION = 500;
    static final int MAX_TURNS = 6;

    record Section(String title, String page, String body) {}

    private final GroqClient groq;
    private final SarvamClient sarvam;
    private final CallNoteService quota;
    private final List<Section> sections;
    private final String guide;

    public HelpChatService(GroqClient groq, SarvamClient sarvam, CallNoteService quota) throws IOException {
        this.groq = groq; this.sarvam = sarvam; this.quota = quota;
        this.guide = new String(new ClassPathResource("help/bdr-guide.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        this.sections = parse(guide);
    }

    static List<Section> parse(String md) {
        List<Section> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^## (.+)\\R(?:Page: (\\S+)\\R)?([\\s\\S]*?)(?=^## |\\z)").matcher(md);
        while (m.find()) out.add(new Section(m.group(1).trim(), m.group(2) == null ? "/" : m.group(2).trim(), m.group(3).trim()));
        return out;
    }

    public boolean enabled() { return groq.enabled() || sarvam.enabled(); }

    public record Turn(String role, String text) {}
    public record Source(String title, String page) {}
    public record Answer(String answer, List<Source> sources, String engine) {}

    public Answer ask(UUID userId, String question, List<Turn> history, String currentPage) {
        if (!enabled()) throw new DomainException("AI_DISABLED", "Ask Prosperr needs an AI key on this server.");
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) throw new DomainException("EMPTY", "Type a question.");
        if (q.length() > MAX_QUESTION) q = q.substring(0, MAX_QUESTION);
        quota.checkQuota(userId);
        String system = system(currentPage, language(q), guide);
        List<Turn> recent = history == null ? List.of() : history.subList(Math.max(0, history.size() - MAX_TURNS), history.size());
        String focused = system(currentPage, language(q), relevant(q + " " + recent.stream().filter(t -> !"assistant".equals(t.role())).map(Turn::text).reduce("", (a, b) -> a + " " + b)));
        String raw = null;
        String engine = null;
        if (groq.enabled()) {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", focused));   // fewer tokens: more questions fit in Groq's per-minute limit
            for (Turn t : recent) msgs.add(Map.of("role", "assistant".equals(t.role()) ? "assistant" : "user", "content", clip(CallNoteService.redact(t.text()))));
            msgs.add(Map.of("role", "user", "content", CallNoteService.redact(q)));
            try {
                raw = groq.chat(msgs, 900);
                engine = "groq";
            } catch (DomainException e) {
                if (!sarvam.enabled()) throw e;                // Groq busy (free-plan limit) or down: answer with Sarvam instead
            }
        }
        if (raw == null) {
            StringBuilder convo = new StringBuilder();
            for (Turn t : recent) convo.append("assistant".equals(t.role()) ? "Assistant: " : "BDR: ").append(clip(CallNoteService.redact(t.text()))).append("\n");
            raw = sarvam.chat(system, (convo.isEmpty() ? "" : "Conversation so far:\n" + convo + "\n") + "BDR's question: " + CallNoteService.redact(q), 900);
            engine = groq.enabled() ? "sarvam (groq busy)" : "sarvam";
        }
        return finish(raw, engine);
    }

    static final Map<String, String> PAGES = new LinkedHashMap<>();
    static {
        PAGES.put("Home", "/"); PAGES.put("My Day", "/my-day"); PAGES.put("Accounts", "/accounts"); PAGES.put("My leads", "/leads");
        PAGES.put("SQLs", "/sql"); PAGES.put("Sequences", "/sequences"); PAGES.put("Event QR", "/event-qr"); PAGES.put("Tasks", "/tasks");
    }

    /** The reply language from the question's script; Latin script means English (Hinglish stays Hinglish). */
    static String language(String q) {
        int[][] blocks = {{0x0900, 0x097F}, {0x0980, 0x09FF}, {0x0A00, 0x0A7F}, {0x0A80, 0x0AFF}, {0x0B00, 0x0B7F}, {0x0B80, 0x0BFF},
                {0x0C00, 0x0C7F}, {0x0C80, 0x0CFF}, {0x0D00, 0x0D7F}};
        String[] names = {"Hindi (or Marathi if the question is Marathi), in Devanagari script", "Bengali", "Punjabi (Gurmukhi)", "Gujarati", "Odia",
                "Tamil", "Telugu", "Kannada", "Malayalam"};
        int[] count = new int[blocks.length];
        q.codePoints().forEach(c -> { for (int i = 0; i < blocks.length; i++) if (c >= blocks[i][0] && c <= blocks[i][1]) count[i]++; });
        int best = -1;
        for (int i = 0; i < count.length; i++) if (count[i] > 0 && (best < 0 || count[i] > count[best])) best = i;
        if (best >= 0) return names[best];
        boolean hinglish = Pattern.compile("(?i)\\b(kaise|kaisa|kya|kyu|kyun|kyon|hai|hain|karu|karun|karna|karte|mera|meri|mere|mujhe|nahi|nhi|kab|kahan|kidhar|aur|batao|bata|wala|wali|hota|hoga|pe|ko|se)\\b")
                .matcher(q).results().count() >= 2;
        return hinglish ? "Hinglish (Hindi written in English letters, like the question)" : "English";
    }

    /** Links may only point at real pages; "/home" and the like become the right path or plain text. */
    static String fixLinks(String text) {
        Matcher m = Pattern.compile("\\[([^\\]]+)]\\((/[^)\\s]*)\\)").matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String label = m.group(1), path = m.group(2);
            String fixed = PAGES.containsValue(path) ? path : PAGES.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(label) || path.replaceAll("[^a-z]", "").equals(e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")))
                    .map(Map.Entry::getValue).findFirst().orElse(path.equals("/home") || path.equals("/dashboard") ? "/" : null);
            m.appendReplacement(b, Matcher.quoteReplacement(fixed == null ? "**" + label + "**" : "[" + label + "](" + fixed + ")"));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "how", "what", "why", "can", "does", "with", "this", "that", "from", "into",
            "when", "where", "who", "you", "your", "are", "was", "kaise", "kya", "hai", "kar", "karu", "karna", "mera", "meri", "mere", "nahi", "kyu", "kyon");

    /** The guide sections that share the most words with the question (plus Common problems); the whole guide if none match. */
    String relevant(String question) {
        Set<String> words = new HashSet<>();
        for (String w : question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (w.length() >= 3 && !STOP.contains(w)) words.add(w.length() > 5 ? w.substring(0, 5) : w);
        if (words.isEmpty()) return guide;
        record Scored(Section s, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Section sec : sections) {
            String title = sec.title().toLowerCase(Locale.ROOT), body = sec.body().toLowerCase(Locale.ROOT);
            int sc = 0;
            for (String w : words) { if (title.contains(w)) sc += 5; if (body.contains(w)) sc += 1; }
            scored.add(new Scored(sec, sc));
        }
        scored.sort((a, b) -> b.score() - a.score());
        if (scored.get(0).score() == 0) return guide;
        StringBuilder b = new StringBuilder("# Prosperr Sales CRM: guide for BDRs (the sections that matter for this question)\n\n");
        Set<String> used = new HashSet<>();
        for (Scored x : scored.subList(0, Math.min(6, scored.size()))) if (x.score() > 0 && used.add(x.s().title()))
            b.append("## ").append(x.s().title()).append("\nPage: ").append(x.s().page()).append("\n").append(x.s().body()).append("\n\n");
        sections.stream().filter(x -> x.title().equals("Common problems") && used.add(x.title()))
                .forEach(x -> b.append("## ").append(x.title()).append("\nPage: ").append(x.page()).append("\n").append(x.body()).append("\n\n"));
        return b.toString();
    }

    String system(String currentPage, String language, String guideText) {
        return """
                You are "Ask Prosperr", the help assistant inside Prosperr's sales CRM, talking to a BDR. Answer questions about how to
                use the CRM: where things are, what a button or rule does, why something happens, and how to do a task step by step.

                Rules:
                - Use ONLY the guide below. If the guide does not cover it, say you are not sure and suggest asking their manager. Never
                  invent buttons, pages, numbers or rules.
                - You cannot see or change the BDR's data. If asked about their own numbers or a specific company, say where in the CRM
                  to find it (with a link) instead of guessing.
                - If the question is not about the CRM or the BDR's sales work, politely say you can only help with the CRM.
                - Reply in %s. Keep words like L1, SQL, BANT, button and page names in English.
                - Be short and practical: 1 to 6 sentences, or short numbered steps for a how-to. Use **bold** for button and page names.
                - Link pages as markdown, using only these paths: %s.
                - Finish with one last line exactly like: SOURCES: <section title>; <section title>  (the guide sections you used, at most 3).

                The BDR is now on the page: %s

                GUIDE:
                %s""".formatted(language, String.join(", ", PAGES.entrySet().stream().map(e -> "[" + e.getKey() + "](" + e.getValue() + ")").toList()),
                currentPage == null || currentPage.isBlank() ? "/" : currentPage, guideText);
    }

    Answer finish(String raw, String engine) {
        String text = (raw == null ? "" : raw).replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?m)^#{1,6}\\s*", "").strip();
        List<Source> sources = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)\\s*\\**SOURCES?\\**:\\s*(.+)$").matcher(text);
        if (m.find()) {
            for (String t : m.group(1).split("[;|\\n]")) {
                String title = t.strip().replaceAll("^[\"'<]|[\"'>.]$", "");
                sections.stream().filter(s -> s.title().equalsIgnoreCase(title)).findFirst()
                        .ifPresent(s -> { if (sources.stream().noneMatch(x -> x.title().equals(s.title()))) sources.add(new Source(s.title(), s.page())); });
            }
            text = text.substring(0, m.start()).strip();
        }
        text = fixLinks(text);
        if (text.isBlank()) text = "Sorry, I couldn't answer that. Try asking in a different way.";
        return new Answer(text, sources, engine);
    }

    private static String clip(String s) { return s == null ? "" : s.length() > 800 ? s.substring(0, 800) : s; }

    /** Questions shown before the first message. */
    public static List<String> suggestions() {
        return List.of("How do I submit an SQL?", "What does each call outcome do?", "Why can't I tick the deal checklist?",
                "How do I import leads from Excel?", "How does Event QR work?", "Why is my sequence paused?");
    }
}
