package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.shared.domain.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Visiting card photo to a contact (Sarvam Vision). Reads name, title, company, emails, phones, website and city,
 * with a confidence per field, and says whether the person or company is already in the CRM. Saves nothing: the BDR
 * checks the fields and saves through the normal account and stakeholder screens.
 */
@Service
public class CardScanService {
    static final long MAX_BYTES = 10_000_000;
    static final String SCHEMA = """
            {"type":"object","properties":{
              "fullName":{"type":"string","description":"The person's full name printed on the card"},
              "designation":{"type":"string","description":"The person's complete job title exactly as printed, including any function or area after a dash, comma or space (for example 'Vice President - Human Resources', 'AVP Finance', 'Senior Manager, Compensation & Benefits')"},
              "company":{"type":"string","description":"The complete company name as printed, including every word of the name such as Labs, Fintech, Analytics or Industries (a single word under a logo that completes the name, like FINTECH, is part of it); leave out Pvt Ltd, Private Limited, LLP, and any tagline or description of what the company does (for example 'Payroll & HR Technology')"},
              "emails":{"type":"array","items":{"type":"string","description":"an email address"},"description":"All email addresses on the card"},
              "phones":{"type":"array","items":{"type":"string","description":"a phone or mobile number"},"description":"All phone, mobile and landline numbers on the card"},
              "website":{"type":"string","description":"Company website or domain"},
              "linkedin":{"type":"string","description":"LinkedIn profile URL if printed"},
              "city":{"type":"string","description":"City from the address"}
            }}""";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final SarvamClient sarvam;
    private final CallNoteService quota;
    private final JdbcTemplate jdbc;

    public CardScanService(SarvamClient sarvam, CallNoteService quota, JdbcTemplate jdbc) {
        this.sarvam = sarvam; this.quota = quota; this.jdbc = jdbc;
    }

    public Map<String, Object> scan(UUID userId, byte[] image, String filename, String contentType) {
        if (!sarvam.enabled()) throw new DomainException("AI_DISABLED", "Card scanning needs the AI key on this server.");
        if (image == null || image.length < 1000) throw new DomainException("BAD_IMAGE", "That photo is empty. Try again.");
        if (image.length > MAX_BYTES) throw new DomainException("BAD_IMAGE", "That photo is over 10 MB. Take it again at a lower size.");
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.equals("image/jpeg") && !type.equals("image/png") && !type.equals("application/pdf"))
            throw new DomainException("BAD_IMAGE", "Use a JPG or PNG photo of the card.");
        quota.checkQuota(userId);
        SarvamClient.Extraction x = sarvam.extract(image, filename == null ? "card" : filename, type, SCHEMA);
        JsonNode r = x.result();

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("fullName", text(r, "fullName"));
        card.put("designation", text(r, "designation"));
        card.put("department", department(null, text(r, "designation")));
        card.put("company", tidyCompany(text(r, "company")));
        List<String> emails = new ArrayList<>();
        r.path("emails").forEach(e -> { var m = EMAIL.matcher(e.asText("")); if (m.find()) emails.add(m.group().toLowerCase(Locale.ROOT)); });
        card.put("emails", emails.stream().distinct().toList());
        List<String> phones = new ArrayList<>();
        r.path("phones").forEach(p -> { String v = p.asText("").trim(); if (v.replaceAll("\\D", "").length() >= 8) phones.add(v); });
        card.put("phones", phones.stream().distinct().toList());
        String website = text(r, "website");
        if (website == null && !emails.isEmpty() && !freeMail(emails.get(0))) website = emails.get(0).substring(emails.get(0).indexOf('@') + 1);
        card.put("website", website);
        card.put("linkedin", text(r, "linkedin"));
        card.put("city", text(r, "city"));

        Map<String, Object> confidence = new LinkedHashMap<>();
        x.annotations().fields().forEachRemaining(e -> {
            JsonNode a = e.getValue();
            double c = a.isArray() ? min(a) : a.path("confidence").asDouble(1);
            confidence.put(e.getKey(), Math.round(c * 100) / 100.0);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("card", card);
        out.put("confidence", confidence);
        out.put("matches", matches(userId, emails, phones, website, (String) card.get("company")));
        return out;
    }

    /** Is the person or the company already in the CRM? Only the essentials: no other BDR's contact details. */
    Map<String, Object> matches(UUID userId, List<String> emails, List<String> phones, String website, String company) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> person = new ArrayList<>();
        for (String e : emails) person.addAll(jdbc.queryForList("""
                SELECT s.id AS "stakeholderId", s.first_name || coalesce(' ' || s.last_name, '') AS name, a.id AS "accountId", a.name AS company
                  FROM crm.stakeholder_email se JOIN crm.stakeholder s ON s.id = se.stakeholder_id JOIN crm.account a ON a.id = s.account_id
                 WHERE se.email_normalized = ? LIMIT 1""", e));
        for (String p : phones) {
            String digits = p.replaceAll("\\D", "");
            if (digits.length() < 10) continue;
            person.addAll(jdbc.queryForList("""
                    SELECT s.id AS "stakeholderId", s.first_name || coalesce(' ' || s.last_name, '') AS name, a.id AS "accountId", a.name AS company
                      FROM crm.stakeholder_phone sp JOIN crm.stakeholder s ON s.id = sp.stakeholder_id JOIN crm.account a ON a.id = s.account_id
                     WHERE right(sp.phone_e164, 10) = ? LIMIT 1""", digits.substring(digits.length() - 10)));
        }
        m.put("person", person.isEmpty() ? null : person.get(0));
        String domain = website == null ? null : website.toLowerCase(Locale.ROOT).replaceAll("^https?://", "").replaceAll("^www\\.", "").replaceAll("/.*$", "");
        List<Map<String, Object>> acc = domain == null ? List.of() : jdbc.queryForList(
                "SELECT id, name FROM crm.account WHERE domain = ? LIMIT 1", domain);
        if (acc.isEmpty() && company != null) acc = jdbc.queryForList(
                "SELECT id, name FROM crm.account WHERE name_normalized = lower(regexp_replace(btrim(?), '\\s+', ' ', 'g')) LIMIT 1", company);
        m.put("company", acc.isEmpty() ? null : acc.get(0));
        return m;
    }

    static String text(JsonNode r, String f) {
        String v = r.path(f).asText("").trim();
        return v.isEmpty() || v.equalsIgnoreCase("null") || v.equalsIgnoreCase("n/a") ? null : v;
    }

    /** "NORTHWIND LABS" -> "Northwind Labs"; keeps short all-caps acronyms (TCS, KPMG) as they are. */
    static String tidyCompany(String c) {
        if (c == null) return null;
        if (!c.equals(c.toUpperCase(Locale.ROOT)) || c.replace(" ", "").length() <= 5) return c;
        StringBuilder b = new StringBuilder();
        for (String w : c.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.length() <= 3 && Set.of("llp", "llc", "pvt", "ltd", "inc").contains(w.replace(".", "")) ? w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1)
                    : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    static String department(String dept, String title) {
        String t = ((dept == null ? "" : dept) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        if (t.contains("payroll")) return "PAYROLL";
        if (t.matches(".*\\b(finance|financial|cfo|accounts?|treasury|controller|tax)\\b.*")) return "FINANCE";
        if (t.matches(".*\\b(hr|human|people|chro|talent|reward|compensation|benefits?)\\b.*")) return "HR";
        return null;
    }

    static boolean freeMail(String email) {
        return email.matches(".*@(gmail|yahoo|hotmail|outlook|rediffmail|icloud|proton(mail)?)\\..*");
    }

    private static double min(JsonNode arr) {
        double m = 1;
        for (JsonNode a : arr) m = Math.min(m, a.path("confidence").asDouble(1));
        return m;
    }
}
