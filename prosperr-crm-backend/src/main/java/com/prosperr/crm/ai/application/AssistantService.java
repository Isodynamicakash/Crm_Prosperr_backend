package com.prosperr.crm.ai.application;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Ist;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice assistant for a BDR: a spoken morning brief and answers to questions about their own work, in their language.
 * Read-only: it looks at the BDR's tasks, leads, replies, meetings and targets and never changes anything. Phone
 * numbers and emails are never sent to the AI; each brief or question counts toward the hourly AI limit.
 */
@Service
public class AssistantService {
    static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(Map.entry("en-IN", "English"), Map.entry("hi-IN", "Hindi"),
            Map.entry("bn-IN", "Bengali"), Map.entry("ta-IN", "Tamil"), Map.entry("te-IN", "Telugu"), Map.entry("kn-IN", "Kannada"),
            Map.entry("ml-IN", "Malayalam"), Map.entry("mr-IN", "Marathi"), Map.entry("gu-IN", "Gujarati"), Map.entry("pa-IN", "Punjabi"),
            Map.entry("od-IN", "Odia"));

    private final SarvamClient sarvam;
    private final CallNoteService quota;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Reply> briefCache = new ConcurrentHashMap<>();

    public AssistantService(SarvamClient sarvam, CallNoteService quota, JdbcTemplate jdbc, Clock clock) {
        this.sarvam = sarvam; this.quota = quota; this.jdbc = jdbc; this.clock = clock;
    }

    public record Reply(String question, String text, String language, String audioBase64, Map<String, Object> facts, Instant at) {}

    // ------------------------------------------------------------------ the BDR's day, from the CRM

    /** Everything the assistant may talk about, as plain facts (no phone numbers or emails). */
    public Map<String, Object> facts(UUID userId) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(Ist.ZONE));
        LocalDate today = now.toLocalDate();
        Instant dayStart = today.atStartOfDay(Ist.ZONE).toInstant(), dayEnd = today.plusDays(1).atStartOfDay(Ist.ZONE).toInstant();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bdr", jdbc.queryForObject("SELECT split_part(full_name, ' ', 1) FROM crm.app_user WHERE id = ?", String.class, userId));
        f.put("today", today + " (" + today.getDayOfWeek() + ")");
        f.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));

        String taskSql = """
                SELECT t.title, a.name AS company, s.first_name || coalesce(' ' || s.last_name, '') AS person, s.designation,
                       to_char(t.due_at AT TIME ZONE 'Asia/Kolkata', 'DD Mon HH24:MI') AS due
                  FROM crm.task t JOIN crm.account a ON a.id = t.account_id LEFT JOIN crm.stakeholder s ON s.id = t.stakeholder_id
                 WHERE t.assignee_id = ? AND t.status = 'OPEN' AND %s ORDER BY t.due_at LIMIT 6""";
        f.put("overdueCount", jdbc.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND status = 'OPEN' AND due_at < ?", Integer.class, userId, ts(dayStart)));
        f.put("overdue", jdbc.queryForList(taskSql.formatted("t.due_at < ?"), userId, ts(dayStart)));
        f.put("dueTodayCount", jdbc.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = ? AND status = 'OPEN' AND due_at >= ? AND due_at < ?", Integer.class, userId, ts(dayStart), ts(dayEnd)));
        f.put("dueToday", jdbc.queryForList(taskSql.formatted("t.due_at >= ? AND t.due_at < ?"), userId, ts(dayStart), ts(dayEnd)));

        String leadsWhere = """
                FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id LEFT JOIN crm.designation_map dm ON dm.id = s.designation_map_id
                WHERE s.bdr_owner_id = ? AND s.engagement_status = 'NOT_CONTACTED' AND NOT s.do_not_contact AND a.account_status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM crm.task t WHERE t.stakeholder_id = s.id AND t.status = 'OPEN')""";
        f.put("newLeadsCount", jdbc.queryForObject("SELECT count(*) " + leadsWhere, Integer.class, userId));
        f.put("topNewLeads", jdbc.queryForList("""
                SELECT s.first_name || coalesce(' ' || s.last_name, '') AS person, s.designation, a.name AS company, s.level
                """ + leadsWhere + " ORDER BY coalesce(dm.priority_rank, 8), a.name LIMIT 5", userId));

        f.put("sequenceReplies", jdbc.queryForList("""
                SELECT s.first_name || coalesce(' ' || s.last_name, '') AS person, a.name AS company,
                       to_char(e.reply_received_at AT TIME ZONE 'Asia/Kolkata', 'DD Mon') AS replied
                  FROM crm.sequence_enrollment e JOIN crm.stakeholder s ON s.id = e.stakeholder_id JOIN crm.account a ON a.id = s.account_id
                 WHERE e.enrolled_by = ? AND e.stop_reason = 'REPLY' AND e.reply_received_at > now() - interval '3 days'
                 ORDER BY e.reply_received_at DESC LIMIT 5""", userId));
        f.put("runningSequences", jdbc.queryForObject("SELECT count(*) FROM crm.sequence_enrollment WHERE enrolled_by = ? AND status = 'RUNNING'", Integer.class, userId));
        f.put("revivalsDue", jdbc.queryForObject("""
                SELECT count(*) FROM crm.v_revival_queue v WHERE EXISTS (SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = v.account_id AND ab.bdr_id = ?)""",
                Integer.class, userId));
        f.put("meetingsTodayAndTomorrow", jdbc.queryForList("""
                SELECT m.level, a.name AS company, to_char(m.scheduled_start AT TIME ZONE 'Asia/Kolkata', 'Dy DD Mon HH24:MI') AS at,
                       ae.full_name AS ae, a.next_step AS "lastNextStep"
                  FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id LEFT JOIN crm.app_user ae ON ae.id = m.ae_id
                 WHERE (m.booked_by = ? OR a.sql_credit_bdr_id = ?) AND m.status IN ('SCHEDULED','RESCHEDULED')
                   AND m.scheduled_start >= ? AND m.scheduled_start < ? ORDER BY m.scheduled_start LIMIT 6""",
                userId, userId, ts(dayStart), ts(today.plusDays(2).atStartOfDay(Ist.ZONE).toInstant())));

        LocalDate monthStart = today.withDayOfMonth(1);
        Instant ms = monthStart.atStartOfDay(Ist.ZONE).toInstant(), me = monthStart.plusMonths(1).atStartOfDay(Ist.ZONE).toInstant();
        Integer l1Done = jdbc.queryForObject("""
                SELECT count(*) FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id
                 WHERE m.level = 'L1' AND m.status = 'COMPLETED' AND (m.booked_by = ? OR a.sql_credit_bdr_id = ?)
                   AND m.scheduled_start >= ? AND m.scheduled_start < ?""", Integer.class, userId, userId, ts(ms), ts(me));
        Integer l1Booked = jdbc.queryForObject("""
                SELECT count(*) FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id
                 WHERE m.level = 'L1' AND m.rescheduled_from_id IS NULL AND (m.booked_by = ? OR a.sql_credit_bdr_id = ?)
                   AND m.booked_on >= ? AND m.booked_on < ?""", Integer.class, userId, userId, ts(ms), ts(me));
        List<Double> target = jdbc.queryForList("""
                SELECT target_value::float8 FROM crm.target WHERE user_id = ? AND metric = 'L1_DONE' AND period_type = 'MONTH' AND period_start = ?""",
                Double.class, userId, monthStart);
        int workDaysLeft = 0;
        for (LocalDate d = today; d.isBefore(monthStart.plusMonths(1)); d = d.plusDays(1))
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) workDaysLeft++;
        Map<String, Object> tgt = new LinkedHashMap<>();
        tgt.put("month", monthStart.getMonth().toString());
        tgt.put("l1DoneTarget", target.isEmpty() ? null : Math.round(target.get(0)));
        tgt.put("l1Done", l1Done);
        tgt.put("l1BookedThisMonth", l1Booked);
        tgt.put("workingDaysLeft", workDaysLeft);
        f.put("target", tgt);
        f.put("callsToday", jdbc.queryForObject("SELECT count(*) FROM crm.activity WHERE logged_by = ? AND type = 'CALL' AND occurred_at >= ?", Integer.class, userId, ts(dayStart)));
        return f;
    }

    /** Companies the question names, with their state: only companies this BDR owns or that are in the pipeline. */
    List<Map<String, Object>> companiesMentioned(UUID userId, String question) {
        String q = question.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> hits = jdbc.queryForList("""
                SELECT a.id, a.name, a.lifecycle_stage AS stage, a.account_status AS status, a.next_step AS "nextStep",
                       to_char(a.last_activity_at AT TIME ZONE 'Asia/Kolkata', 'DD Mon YYYY') AS "lastActivity", ae.full_name AS ae
                  FROM crm.account a LEFT JOIN crm.app_user ae ON ae.id = a.ae_owner_id
                 WHERE length(split_part(a.name_normalized, ' ', 1)) >= 4
                   AND (strpos(?, a.name_normalized) > 0 OR ? ~ ('\\m' || regexp_replace(split_part(a.name_normalized, ' ', 1), '([^a-z0-9])', '\\\1', 'g') || '\\M'))
                   AND (a.visibility_zone = 'PIPELINE' OR EXISTS (SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = a.id AND ab.bdr_id = ?))
                 ORDER BY (strpos(?, a.name_normalized) > 0) DESC, length(a.name_normalized) DESC LIMIT 2""", q, q, userId, q);
        for (Map<String, Object> h : hits) {
            UUID id = (UUID) h.remove("id");
            h.put("contacts", jdbc.queryForList("""
                    SELECT s.first_name || coalesce(' ' || s.last_name, '') AS person, s.designation, s.engagement_status AS status
                      FROM crm.stakeholder s WHERE s.account_id = ? ORDER BY s.level NULLS LAST LIMIT 5""", id));
            h.put("recentNotes", jdbc.queryForList("""
                    SELECT to_char(x.occurred_at AT TIME ZONE 'Asia/Kolkata', 'DD Mon') AS on, u.full_name AS by, x.type, left(x.remarks, 160) AS note
                      FROM crm.activity x JOIN crm.app_user u ON u.id = x.logged_by WHERE x.account_id = ? AND x.remarks IS NOT NULL
                     ORDER BY x.occurred_at DESC LIMIT 3""", id));
        }
        return hits;
    }

    // ------------------------------------------------------------------ brief and questions

    /** The morning brief, spoken. Cached for 15 minutes per person and language so replaying costs nothing. */
    public Reply brief(UUID userId, String language, boolean refresh) {
        String lang = LANGUAGE_NAMES.containsKey(language) ? language : "en-IN";
        String key = userId + "|" + lang;
        Reply cached = briefCache.get(key);
        if (!refresh && cached != null && cached.at().isAfter(clock.instant().minus(Duration.ofMinutes(15)))) return cached;
        Map<String, Object> f = facts(userId);
        String text;
        if (sarvam.enabled()) {
            quota.checkQuota(userId);
            text = clean(sarvam.chat(system(lang) + """

                    Turn the points below into the BDR's spoken morning brief, about 80 to 120 words. Use only these points, in
                    this order, and add nothing else: no other people, companies, numbers or advice. Start with a short greeting by
                    first name and end with one short encouraging line.""",
                    "Points:\n" + String.join("\n", points(f)), 900));
        } else {
            text = template(f);
        }
        Reply r = new Reply(null, text, lang, audio(text, lang), f, clock.instant());
        briefCache.put(key, r);
        return r;
    }

    /** A question by voice (WAV) or text; answered from the BDR's own CRM data, in the language they spoke. */
    public Reply ask(UUID userId, byte[] wav, String typed, String language) {
        if (!sarvam.enabled()) throw new DomainException("AI_DISABLED", "The voice assistant needs the AI key on this server.");
        quota.checkQuota(userId);
        String question = typed;
        String lang = LANGUAGE_NAMES.containsKey(language) ? language : null;
        if (wav != null && wav.length > 0) {
            quota.checkAudio(wav);
            SarvamClient.Transcript t = sarvam.transcribeWithLanguage(wav);
            question = t.text();
            if (t.language() != null && LANGUAGE_NAMES.containsKey(t.language())) lang = t.language();
        }
        if (question == null || question.isBlank()) throw new DomainException("NOTHING_HEARD", "I didn't catch that. Try again a little closer to the mic.");
        if (lang == null) lang = "en-IN";
        Map<String, Object> f = facts(userId);
        List<Map<String, Object>> companies = companiesMentioned(userId, question);
        String text = clean(sarvam.chat(system(lang) + """

                Answer the BDR's question from the CRM data only, in 2 to 5 short spoken sentences. If they ask whom to call or
                what to do first, read out the "call list" exactly in its order (up to five, with its reasons); do not reorder it or
                add anyone. If the data does not answer the question, say so plainly and suggest where in the CRM to look. You
                cannot change anything; if asked to, say which screen to use.""",
                "Today's points:\n" + String.join("\n", points(f)) + "\n\nCall list (in order):\n" + String.join("\n", callList(f))
                        + "\n\nCRM data (empty sections left out):\n" + spoken(f) + (companies.isEmpty() ? "" : "\nCompanies mentioned:\n" + companies)
                        + "\n\nQuestion: \"" + CallNoteService.redact(question) + "\"", 700));
        return new Reply(question, text, lang, audio(text, lang), f, clock.instant());
    }

    // ------------------------------------------------------------------ helpers

    private String system(String lang) {
        String name = LANGUAGE_NAMES.get(lang);
        return "You are the voice assistant inside Prosperr's sales CRM, talking to a BDR (business development rep) who books "
                + "first meetings (L1) with CFOs and HR heads for Prosperr's flexible benefits plan. Reply in " + name
                + (lang.equals("en-IN") ? "" : ", written in " + name + " script, keeping company names, people's names and words like L1, SQL, "
                + "follow-up and sequence in English") + ". Speak naturally, like a helpful colleague: short sentences, no markdown, no "
                + "bullet symbols, no emojis, no headings. Never invent people, companies or numbers that are not in the data. Never say "
                + "field names from the data (like overdueCount); say what they mean. An L1 is a first meeting, not a call. If a section is missing from the data, don't mention it.";
    }

    /** The brief as plain English points, from data only; the AI just says them naturally in the chosen language. */
    @SuppressWarnings("unchecked")
    static List<String> points(Map<String, Object> f) {
        List<String> p = new ArrayList<>();
        p.add("Name: " + f.get("bdr") + ".");
        int overdue = ((Number) f.get("overdueCount")).intValue();
        if (overdue > 0) p.add(overdue + " follow-up" + (overdue == 1 ? " is" : "s are") + " overdue. Start with: " + describe((List<Map<String, Object>>) f.get("overdue"), 2) + ".");
        List<Map<String, Object>> replies = (List<Map<String, Object>>) f.get("sequenceReplies");
        if (!replies.isEmpty()) p.add(replies.size() + " " + (replies.size() == 1 ? "person" : "people") + " replied to your email sequences: "
                + join(replies, r -> r.get("person") + " at " + r.get("company"), 3) + ". Call them today.");
        int dueToday = ((Number) f.get("dueTodayCount")).intValue();
        if (dueToday > 0) p.add(dueToday + " follow-up" + (dueToday == 1 ? " is" : "s are") + " due today, including " + describe((List<Map<String, Object>>) f.get("dueToday"), 2) + ".");
        List<Map<String, Object>> meetings = (List<Map<String, Object>>) f.get("meetingsTodayAndTomorrow");
        if (!meetings.isEmpty()) p.add("Meetings coming up: " + join(meetings, m -> m.get("level") + " with " + m.get("company") + " on " + m.get("at")
                + (m.get("ae") == null ? "" : " with AE " + m.get("ae")), 3) + ".");
        int leads = ((Number) f.get("newLeadsCount")).intValue();
        if (leads > 0) p.add(leads + " new lead" + (leads == 1 ? " has" : "s have") + " not been called yet. Most senior first: "
                + join((List<Map<String, Object>>) f.get("topNewLeads"), l -> l.get("person") + (l.get("designation") == null ? "" : ", " + l.get("designation")) + " at " + l.get("company"), 2) + ".");
        int revivals = ((Number) f.get("revivalsDue")).intValue();
        if (revivals > 0) p.add(revivals + " inactive compan" + (revivals == 1 ? "y is" : "ies are") + " due for a fresh try; they are under Revival in My Day.");
        Map<String, Object> t = (Map<String, Object>) f.get("target");
        int done = ((Number) t.get("l1Done")).intValue(), days = ((Number) t.get("workingDaysLeft")).intValue();
        if (t.get("l1DoneTarget") != null) {
            long target = ((Number) t.get("l1DoneTarget")).longValue(), left = Math.max(0, target - done);
            p.add(left == 0 ? "L1 target for " + cap(t.get("month")) + " is reached: " + done + " done against " + target + "."
                    : "L1 target for " + cap(t.get("month")) + ": " + done + " L1 meetings done of " + target + ", " + left + " more L1 meetings needed with " + days
                    + " working day" + (days == 1 ? "" : "s") + " left, so about " + (days == 0 ? left : (left + days - 1) / days) + " L1 meetings a day.");
        } else if (done > 0) {
            p.add(done + " L1 meeting" + (done == 1 ? "" : "s") + " done this month.");
        }
        int calls = ((Number) f.get("callsToday")).intValue();
        if (calls > 0) p.add(calls + " call" + (calls == 1 ? "" : "s") + " logged so far today.");
        return p;
    }

    /** Whom to call first, in order: overdue follow-ups, replies, due today, then the most senior new leads. */
    @SuppressWarnings("unchecked")
    static List<String> callList(Map<String, Object> f) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) f.get("overdue")) out.add(line(t) + " (overdue since " + t.get("due") + ")");
        for (Map<String, Object> r : (List<Map<String, Object>>) f.get("sequenceReplies")) out.add(r.get("person") + " at " + r.get("company") + " (replied to your email)");
        for (Map<String, Object> t : (List<Map<String, Object>>) f.get("dueToday")) out.add(line(t) + " (due today " + t.get("due") + ")");
        for (Map<String, Object> l : (List<Map<String, Object>>) f.get("topNewLeads"))
            out.add(l.get("person") + (l.get("designation") == null ? "" : ", " + l.get("designation")) + " at " + l.get("company") + " (new lead, most senior first)");
        List<String> top = out.subList(0, Math.min(5, out.size()));
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) numbered.add((i + 1) + ". " + top.get(i));
        return numbered.isEmpty() ? List.of("Nothing is waiting: no follow-ups and no uncalled leads.") : numbered;
    }

    private static String line(Map<String, Object> t) {
        Object person = t.get("person");
        return (person == null ? "" : person + (t.get("designation") == null ? "" : ", " + t.get("designation")) + " at ") + t.get("company") + ": " + t.get("title");
    }

    private static String describe(List<Map<String, Object>> tasks, int n) { return join(tasks, AssistantService::line, n); }

    private static String join(List<Map<String, Object>> rows, java.util.function.Function<Map<String, Object>, String> f, int n) {
        return String.join("; ", rows.stream().limit(n).map(f).toList());
    }

    private static String cap(Object month) {
        String m = String.valueOf(month).toLowerCase(Locale.ROOT);
        return m.isEmpty() ? m : Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    /** The facts with empty lists and zero counts removed, so the AI never talks about what isn't there. */
    static Map<String, Object> spoken(Map<String, Object> f) {
        Map<String, Object> out = new LinkedHashMap<>();
        f.forEach((k, v) -> {
            if (v instanceof Collection<?> c && c.isEmpty()) return;
            if (v instanceof Number n && n.intValue() == 0 && !k.equals("callsToday")) return;
            out.put(k, v);
        });
        return out;
    }

    /** Strips reasoning tags and markdown the voice would read out. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*#_`>]+", "").replaceAll("\n{3,}", "\n\n").strip();
    }

    private String audio(String text, String lang) {
        if (!sarvam.enabled() || text.isBlank()) return null;
        try { return Base64.getEncoder().encodeToString(sarvam.speak(text, lang)); }
        catch (RuntimeException e) { return null; }                        // text still shows if the voice fails
    }

    /** Plain English brief when no AI key is set, so the card still works. */
    static String template(Map<String, Object> f) {
        StringBuilder b = new StringBuilder("Good morning " + f.get("bdr") + ". ");
        b.append("You have ").append(f.get("overdueCount")).append(" overdue follow-ups and ").append(f.get("dueTodayCount")).append(" due today. ");
        b.append(f.get("newLeadsCount")).append(" new leads are waiting. ");
        @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) f.get("target");
        if (t.get("l1DoneTarget") != null) b.append("L1 done this month: ").append(t.get("l1Done")).append(" of ").append(t.get("l1DoneTarget")).append(". ");
        return b.toString().trim();
    }

    private static java.sql.Timestamp ts(Instant i) { return java.sql.Timestamp.from(i); }
}
