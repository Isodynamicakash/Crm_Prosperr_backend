package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.domain.BusinessRelevance;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Ist;
import com.prosperr.crm.shared.domain.MeetingOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the meeting-outcome, new-stakeholder and SQL forms from a voice note or pasted text in any of the
 * languages Sarvam understands. Like the call log, it only suggests: every value is checked against the allowed
 * options and the person still reviews and saves the form.
 */
@Service
public class FormFillService {
    public enum Form { MEETING, STAKEHOLDER, SQL }

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w])\\+?\\d[\\d\\s().-]{7,}\\d(?![\\w])");
    private static final Pattern LINKEDIN = Pattern.compile("(?:https?://)?(?:[a-z]{2,3}\\.)?linkedin\\.com/in/[A-Za-z0-9_%-]+/?", Pattern.CASE_INSENSITIVE);

    private final SarvamClient sarvam;
    private final CallNoteService callNotes;   // shared limits and redaction
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public FormFillService(SarvamClient sarvam, CallNoteService callNotes, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.sarvam = sarvam; this.callNotes = callNotes; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    public Map<String, Object> fromAudio(UUID userId, Form form, byte[] wav) {
        callNotes.checkAudio(wav);
        callNotes.checkQuota(userId);
        var t = sarvam.transcribeWithLanguage(wav);
        if (t.text().isBlank()) throw new DomainException("AUDIO_UNCLEAR", "Couldn't hear anything clearly. Try again a bit closer to the mic.");
        Map<String, Object> out = fill(form, t.text());
        out.put("language", t.language());
        return out;
    }

    public Map<String, Object> fromText(UUID userId, Form form, String text) {
        callNotes.checkText(text);
        callNotes.checkQuota(userId);
        Map<String, Object> out = fill(form, text.trim());
        out.put("language", null);
        return out;
    }

    Map<String, Object> fill(Form form, String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transcript", text);
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        String day = CallNoteService.calendar(today);
        String sys = "You fill a B2B sales CRM form from a short note. The note may be in English, any Indian language, or a mix. "
                + "Reply with exactly one JSON object and nothing else. Use null for anything the note does not say. Write free text in English.";
        switch (form) {
            case MEETING -> {
                JsonNode n = ask(sys, day + "Meeting outcome options: " + String.join(", ", names(MeetingOutcome.values()))
                        + " (COMPLETED_QUALIFIED = held and moving ahead; COMPLETED_FOLLOW_UP = held, needs another follow-up; "
                        + "COMPLETED_NOT_INTERESTED = held, client not interested; NO_SHOW = client didn't join; RESCHEDULED; DISQUALIFIED = not a fit).\n"
                        + note(text) + "Return {\"outcome\", \"notes\": key points in 1-3 sentences, \"nextStep\": short action, "
                        + "\"nextStepDue\": \"YYYY-MM-DD\" or null}.");
                out.put("outcome", oneOf(str(n, "outcome"), names(MeetingOutcome.values())));
                out.put("notes", clip(str(n, "notes"), 1000));
                out.put("nextStep", clip(str(n, "nextStep"), 200));
                out.put("nextStepDue", futureDate(str(n, "nextStepDue"), today));
            }
            case SQL -> {
                Map<String, List<String>> bant = new LinkedHashMap<>();
                for (String k : List.of("BANT_BUDGET", "BANT_AUTHORITY", "BANT_NEED", "BANT_TIMELINE")) bant.put(k, codes(k));
                var opts = new StringBuilder();
                bant.forEach((k, v) -> opts.append(k).append(": ").append(String.join(" | ", labelled(k))).append('\n'));
                JsonNode n = ask(sys, day + "Qualification options (use the CODE before '='):\n" + opts
                        + "BUSINESS_RELEVANCE: FBP (flexible benefits) | PAYROLL | BOTH\n" + note(text)
                        + "Return {\"budget\", \"authority\", \"need\", \"timeline\", \"businessRelevance\", "
                        + "\"budgetNote\", \"authorityNote\", \"needNote\", \"timelineNote\"} where the notes are a few words each.");
                out.put("budget", oneOf(str(n, "budget"), bant.get("BANT_BUDGET")));
                out.put("authority", oneOf(str(n, "authority"), bant.get("BANT_AUTHORITY")));
                out.put("need", oneOf(str(n, "need"), bant.get("BANT_NEED")));
                out.put("timeline", oneOf(str(n, "timeline"), bant.get("BANT_TIMELINE")));
                out.put("businessRelevance", oneOf(str(n, "businessRelevance"), names(BusinessRelevance.values())));
                for (String k : List.of("budgetNote", "authorityNote", "needNote", "timelineNote")) out.put(k, clip(str(n, k), 200));
            }
            case STAKEHOLDER -> {
                // Contact details are read here with patterns, never sent to the AI provider.
                out.put("emails", distinct(EMAIL, text, 3, s -> s.toLowerCase(Locale.ROOT)));
                out.put("phones", distinct(PHONE, text, 3, s -> s.replaceAll("[^0-9+]", "")));
                List<String> li = distinct(LINKEDIN, text, 1, s -> s.startsWith("http") ? s : "https://" + s);
                out.put("linkedinUrl", li.isEmpty() ? null : li.get(0));
                JsonNode n = ask(sys, "Departments: FINANCE, HR, PAYROLL, OTHER.\n" + note(CallNoteService.redact(text))
                        + "This is a person's details (e-mail signature, visiting card, LinkedIn headline or spoken intro). "
                        + "Return {\"fullName\", \"designation\": their job title as written, \"department\"}.");
                out.put("fullName", clip(str(n, "fullName"), 120));
                out.put("designation", clip(str(n, "designation"), 150));
                out.put("department", oneOf(str(n, "department"), names(Department.values())));
            }
        }
        return out;
    }

    private JsonNode ask(String system, String user) {
        String reply = sarvam.chat(system, user);
        Matcher m = JSON_OBJECT.matcher(reply == null ? "" : reply);
        if (!m.find()) throw new DomainException("AI_UNAVAILABLE", "The AI reply could not be read. Fill the form by hand.");
        try { return json.readTree(m.group()); }
        catch (Exception e) { throw new DomainException("AI_UNAVAILABLE", "The AI reply could not be read. Fill the form by hand."); }
    }

    private static String note(String text) { return "Note:\n\"\"\"\n" + CallNoteService.redactNumbersOnly(text) + "\n\"\"\"\n"; }

    private List<String> codes(String key) {
        return jdbc.queryForList("SELECT code FROM crm.picklist_value WHERE picklist_key = ? AND is_active ORDER BY sort_order", String.class, key);
    }

    private List<String> labelled(String key) {
        return jdbc.query("SELECT code, label FROM crm.picklist_value WHERE picklist_key = ? AND is_active ORDER BY sort_order",
                (rs, i) -> rs.getString(1) + "=" + rs.getString(2), key);
    }

    static List<String> names(Enum<?>[] values) { return Arrays.stream(values).map(Enum::name).toList(); }

    static String oneOf(String v, List<String> allowed) {
        if (v == null) return null;
        String u = v.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(u) ? u : null;
    }

    static LocalDate futureDate(String v, LocalDate today) {
        if (v == null) return null;
        try {
            LocalDate d = LocalDate.parse(v.trim());
            return d.isAfter(today) && !d.isAfter(today.plusDays(366)) ? d : null;
        } catch (DateTimeParseException e) { return null; }
    }

    static List<String> distinct(Pattern p, String text, int max, java.util.function.UnaryOperator<String> norm) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find() && out.size() < max) out.add(norm.apply(m.group().trim()));
        return new ArrayList<>(out);
    }

    private static String str(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String clip(String s, int max) { return s == null ? null : s.length() > max ? s.substring(0, max) : s; }
}
