package com.prosperr.crm.outreach.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.DomainException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the seed templates (PRD Appendix A, M3-4, M3-5).
 * <ul>
 *   <li>{{First Name}} empty -> "there"</li>
 *   <li>{{Company}} or sender name empty -> sending is blocked</li>
 *   <li>{{Sender Phone}} empty -> left out of the signature ("Prosperr.io | 98..." becomes "Prosperr.io")</li>
 *   <li>{{Designation}} empty -> the sentence that uses it is left out</li>
 *   <li>{{Product Link}} empty -> the line is left out</li>
 *   <li>{{Call Line}} only when the sequence started from a "Did Not Pick" call (M3-7)</li>
 *   <li>{{Persona Pitch}} chosen by the stakeholder's department (Finance / HR / Payroll)</li>
 * </ul>
 */
public final class TemplateRenderer {

    public record Rendered(String subject, String body) {}

    /** snippets: code -> (variant -> content); variants DEFAULT, FINANCE, HR, PAYROLL. */
    private final Map<String, Map<String, String>> snippets;

    public TemplateRenderer(Map<String, Map<String, String>> snippets) { this.snippets = snippets; }

    public Rendered render(String subject, String body, MergeContext ctx) {
        List<String> missing = new ArrayList<>();
        if (blank(ctx.company())) missing.add("Company");
        if (blank(ctx.senderFirstName()) || blank(ctx.senderFullName())) missing.add("Sender name");
        if (!missing.isEmpty()) {
            throw new DomainException("MERGE_FIELD_MISSING", "Cannot send: missing " + String.join(", ", missing));
        }
        String b = expandSnippets(body, ctx);
        return new Rendered(fields(expandSnippets(subject, ctx), ctx), tidy(fields(b, ctx)));
    }

    private String expandSnippets(String text, MergeContext ctx) {
        String t = text;
        t = t.replace("{{Call Line}}", ctx.startedFromCall() ? snippet("CALL_LINE", "DEFAULT") : "");
        t = t.replace("{{Persona Pitch}}", snippet("PERSONA_PITCH", personaVariant(ctx.department())));
        t = t.replace("{{Customer Proof}}", snippet("CUSTOMER_PROOF", "DEFAULT"));
        t = t.replace("{{Signature}}", snippet("SIGNATURE", "DEFAULT"));
        return t;
    }

    private String fields(String text, MergeContext ctx) {
        String t = text;
        if (blank(ctx.designation())) t = dropSentencesWith(t, "{{Designation}}");
        if (blank(ctx.productLink())) t = dropLinesWith(t, "{{Product Link}}");
        if (blank(ctx.senderPhone())) t = dropLinesWith(t.replaceAll("\\s*[|·,-]\\s*\\{\\{Sender Phone}}", ""), "{{Sender Phone}}");
        t = t.replace("{{First Name}}", blank(ctx.firstName()) ? "there" : ctx.firstName());
        t = t.replace("{{Company}}", ctx.company());
        t = t.replace("{{Designation}}", nz(ctx.designation()));
        t = t.replace("{{Sender First Name}}", ctx.senderFirstName());
        t = t.replace("{{Sender Full Name}}", ctx.senderFullName());
        t = t.replace("{{Sender Phone}}", nz(ctx.senderPhone()));
        t = t.replace("{{Product Link}}", nz(ctx.productLink()));
        t = t.replace("{{Stakeholder Link}}", nz(ctx.stakeholderLink()));
        t = t.replace("{{Call Task Due}}", nz(ctx.callTaskDue()));
        return t;
    }

    static String personaVariant(Department d) {
        return switch (d == null ? Department.OTHER : d) {
            case FINANCE -> "FINANCE";
            case PAYROLL -> "PAYROLL";
            case HR -> "HR";
            case OTHER -> "FINANCE";
        };
    }

    private String snippet(String code, String variant) {
        Map<String, String> v = snippets.getOrDefault(code, Map.of());
        String s = v.get(variant);
        if (s == null) s = v.getOrDefault("DEFAULT", "");
        return s;
    }

    private static String dropLinesWith(String text, String token) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.contains(token)) sb.append(line).append('\n');
        }
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }

    private static String dropSentencesWith(String text, String token) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.contains(token)) {
                String kept = String.join(" ", java.util.Arrays.stream(line.split("(?<=[.!?])\\s+"))
                        .filter(s -> !s.contains(token)).toList());
                out.append(kept);
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** Collapse the blank lines left by removed snippets. */
    private static String tidy(String s) {
        return s.replaceAll("\n{3,}", "\n\n").strip();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
