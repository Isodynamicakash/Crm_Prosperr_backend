package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Ist;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a BDR's spoken (or typed) call note into suggested call-log fields. Only suggests: the BDR reviews the
 * pre-filled form and saves it through the normal call-log endpoint, so every existing rule still applies.
 */
@Service
public class CallNoteService {
    static final int MAX_AUDIO_BYTES = 2_500_000;   // ~75 s of 16 kHz mono WAV; the UI stops at 30 s
    static final int MAX_TEXT_CHARS = 2_000;
    static final int HOURLY_LIMIT = 60;
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d[\\d\\s-]{6,}\\d");

    public record Suggestion(String transcript, String outcomeCode, String outcomeLabel, String remark,
                             LocalDate followUpDate, String reason) {}

    record Outcome(String code, String label, boolean requiresDate, boolean requiresReason) {}

    private final SarvamClient sarvam;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> usage = new ConcurrentHashMap<>();

    public CallNoteService(SarvamClient sarvam, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.sarvam = sarvam; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    public boolean enabled() { return sarvam.enabled(); }

    void checkAudio(byte[] wav) {
        if (wav == null || wav.length < 1_000) throw new DomainException("AUDIO_EMPTY", "The recording is empty. Hold the mic button and speak.");
        if (wav.length > MAX_AUDIO_BYTES) throw new DomainException("AUDIO_TOO_LONG", "Keep voice notes under 30 seconds.");
        if (!(wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F')) throw new DomainException("AUDIO_FORMAT", "Send the recording as WAV audio.");
    }

    void checkText(String text) {
        if (text == null || text.isBlank()) throw new DomainException("NOTE_EMPTY", "Write or say what happened.");
        if (text.length() > MAX_TEXT_CHARS) throw new DomainException("NOTE_TOO_LONG", "Keep the note under 2,000 characters.");
    }

    public Suggestion fromAudio(UUID userId, byte[] wav) {
        checkAudio(wav);
        checkQuota(userId);
        String transcript = sarvam.transcribeWithLanguage(wav).text();
        if (transcript.isBlank()) throw new DomainException("AUDIO_UNCLEAR", "Couldn't hear anything clearly. Try again a bit closer to the mic.");
        return suggest(transcript);
    }

    public Suggestion fromText(UUID userId, String text) {
        checkText(text);
        checkQuota(userId);
        return suggest(text.trim());
    }

    Suggestion suggest(String transcript) {
        Map<String, Outcome> outcomes = outcomes();
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        var list = new StringBuilder();
        outcomes.values().forEach(o -> list.append("- ").append(o.code()).append(": ").append(o.label())
                .append(o.requiresDate() ? " (needs followUpDate)" : "").append(o.requiresReason() ? " (needs reason)" : "").append('\n'));
        String system = "You turn a B2B sales rep's short call note into fields for a CRM call log. The note may be in English, "
                + "Hindi or a mix. Reply with exactly one JSON object and nothing else. Never invent facts that are not in the note.";
        String user = calendar(today)
                + "Allowed outcome codes:\n" + list
                + "\nCall note:\n\"\"\"\n" + redact(transcript) + "\n\"\"\"\n\n"
                + "Return {\"outcomeCode\": one code from the list or null if unclear, "
                + "\"remark\": one or two short factual sentences in English for the call log, "
                + "\"followUpDate\": \"YYYY-MM-DD\" only if the note says when to call back (resolve words like tomorrow or next Monday from today), otherwise null, "
                + "\"reason\": a few words only if the prospect gave a reason for saying no, otherwise null}.";
        JsonNode out = parse(sarvam.chat(system, user));

        String code = text(out, "outcomeCode");
        Outcome outcome = code == null ? null : outcomes.get(code.toUpperCase(Locale.ROOT));
        String remark = clip(text(out, "remark"), 500);
        String reason = clip(text(out, "reason"), 200);
        LocalDate follow = null;
        String d = text(out, "followUpDate");
        if (d != null) {
            try {
                LocalDate parsed = LocalDate.parse(d);
                if (parsed.isAfter(today) && !parsed.isAfter(today.plusDays(366))) follow = parsed;
            } catch (DateTimeParseException ignored) { /* leave empty; the BDR picks a date */ }
        }
        return new Suggestion(transcript, outcome == null ? null : outcome.code(), outcome == null ? null : outcome.label(),
                remark == null ? clip(transcript, 500) : remark, follow, outcome != null && outcome.requiresReason() ? reason : null);
    }

    Map<String, Outcome> outcomes() {
        var map = new LinkedHashMap<String, Outcome>();
        jdbc.query("""
                select p.code, p.label, coalesce(r.requires_date,false) requires_date, coalesce(r.requires_reason,false) requires_reason
                from crm.picklist_value p left join crm.call_outcome_rule r on r.outcome_id = p.id
                where p.picklist_key = 'CALL_OUTCOME' and p.is_active order by p.sort_order""",
                rs -> { map.put(rs.getString("code"), new Outcome(rs.getString("code"), rs.getString("label"),
                        rs.getBoolean("requires_date"), rs.getBoolean("requires_reason"))); });
        return map;
    }

    private JsonNode parse(String reply) {
        Matcher m = JSON_OBJECT.matcher(reply == null ? "" : reply.replaceAll("(?s)<think>.*?</think>", ""));
        if (!m.find()) throw new DomainException("AI_UNAVAILABLE", "The AI reply could not be read. Fill the form by hand.");
        try { return json.readTree(m.group()); }
        catch (Exception e) { throw new DomainException("AI_UNAVAILABLE", "The AI reply could not be read. Fill the form by hand."); }
    }

    void checkQuota(UUID userId) {
        Instant now = clock.instant();
        Deque<Instant> q = usage.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) q.pollFirst();
            if (q.size() >= HOURLY_LIMIT) throw new DomainException("AI_LIMIT", "You've used the AI helper " + HOURLY_LIMIT + " times this hour. Fill the form by hand for now.");
            q.addLast(now);
        }
    }

    /** Today plus the next 14 days with weekday names, so "next Monday" or "Friday" resolve to the right date. */
    static String calendar(LocalDate today) {
        var sb = new StringBuilder("Today is " + today + " (" + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + ", India). Upcoming days: ");
        for (int i = 1; i <= 14; i++) {
            LocalDate d = today.plusDays(i);
            sb.append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append(' ').append(d).append(i < 14 ? ", " : ".\n");
        }
        return sb.toString();
    }

    /** Phone numbers and e-mail addresses never go to the AI provider. */
    static String redact(String s) {
        return LONG_NUMBER.matcher(EMAIL.matcher(s).replaceAll("[email]")).replaceAll("[number]");
    }

    static String redactNumbersOnly(String s) { return redact(s); }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String clip(String s, int max) { return s == null ? null : s.length() > max ? s.substring(0, max) : s; }
}
