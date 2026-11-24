package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.shared.domain.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

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

    private final SarvamClient sarvam;
    private final CallNoteService quota;
    private final JdbcTemplate jdbc;

    public CardScanService(SarvamClient sarvam, CallNoteService quota, JdbcTemplate jdbc) {
        this.sarvam = sarvam; this.quota = quota; this.jdbc = jdbc;
    }

    /** Below this, a key field is read a second time and the two reads are compared. */
    static final double RECHECK_BELOW = 0.7;
    static final List<String> KEY_FIELDS = List.of("fullName", "company", "emails", "phones");

    public Map<String, Object> scan(UUID userId, byte[] image, String filename, String contentType) {
        if (image == null || image.length < 1000) throw new DomainException("BAD_IMAGE", "That photo is empty. Try again.");
        if (image.length > MAX_BYTES) throw new DomainException("BAD_IMAGE", "That photo is over 10 MB. Take it again at a lower size.");
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.equals("image/jpeg") && !type.equals("image/png") && !type.equals("application/pdf"))
            throw new DomainException("BAD_IMAGE", "Use a JPG or PNG photo of the card.");
        List<String> checks = new ArrayList<>();
        Map<String, String> source = new LinkedHashMap<>();

        // 1. a QR on the card is exact data: read it first
        Map<String, Object> qr = new LinkedHashMap<>();
        if (!type.equals("application/pdf")) for (String code : CardRules.qrCodes(image)) CardRules.parseQr(code).forEach(qr::putIfAbsent);
        boolean qrComplete = qr.get("fullName") != null && qr.get("company") != null && (qr.get("emails") != null || qr.get("phones") != null);
        if (!qr.isEmpty()) checks.add(qrComplete ? "Read from the QR code on the card (exact)." : "Part of this came from the QR code on the card.");

        // 2. the AI read, unless the QR already gave everything; a second read settles fields it was unsure of
        Map<String, Object> card = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        if (!qrComplete) {
            if (!sarvam.enabled()) {
                if (qr.isEmpty()) throw new DomainException("AI_DISABLED", "Card scanning needs the AI key on this server.");
            } else {
                quota.checkQuota(userId);
                byte[] prepared = type.equals("application/pdf") ? image : CardRules.prepare(image);
                String readType = prepared == image ? type : "image/png";
                if (prepared != image) checks.add("Small photo: enlarged and sharpened before reading.");
                Read first = read(prepared, filename, readType);
                card.putAll(first.fields()); confidence.putAll(first.confidence());
                List<String> unsure = KEY_FIELDS.stream().filter(k -> present(card.get(k)) && confidence.getOrDefault(k, 1.0) < RECHECK_BELOW).toList();
                if (!unsure.isEmpty()) {
                    Read second = read(prepared, filename, readType);
                    for (String k : unsure) {
                        Object a = card.get(k), b = second.fields().get(k);
                        double ca = confidence.getOrDefault(k, 1.0), cb = second.confidence().getOrDefault(k, 1.0);
                        if (same(a, b)) confidence.put(k, Math.max(ca, cb));   // agreement is not proof: the same misread can repeat, so it stays flagged
                        else if (b != null && cb > ca) { card.put(k, b); confidence.put(k, cb); }
                    }
                    checks.add("Read twice to double-check " + String.join(", ", unsure.stream().map(CardScanService::label).toList()) + ".");
                }
                card.keySet().forEach(k -> source.put(k, "ocr"));
            }
        }
        qr.forEach((k, v) -> { card.put(k, v); confidence.put(k, 1.0); source.put(k, "qr"); });

        // 3. clean up and cross-check
        String company = tidyCompany((String) card.get("company"));
        String website = (String) card.get("website");
        List<String> emails = new ArrayList<>();
        for (Object e : list(card.get("emails"))) {
            String fixed = CardRules.repairEmail(String.valueOf(e));
            if (fixed == null) continue;
            if (!fixed.equalsIgnoreCase(String.valueOf(e).trim())) checks.add("Cleaned up the email " + e + " → " + fixed + ".");
            if (!emails.contains(fixed)) emails.add(fixed);
        }
        String fullName = (String) card.get("fullName");
        emails = new ArrayList<>(ownEmails(emails, website, fullName));
        String webDomain = CardRules.domainOf(website);
        for (int i = 0; i < emails.size(); i++) {
            String e = emails.get(i), agreed = CardRules.agreeDomain(CardRules.domainOf(e), webDomain, company);
            if (agreed == null) continue;
            if (!agreed.equals(CardRules.domainOf(e))) {
                String fixed = e.substring(0, e.indexOf('@') + 1) + agreed;
                checks.add("The email and the website differed by a letter: used " + agreed + " for both (" + e + " → " + fixed + ").");
                emails.set(i, fixed);
            } else {
                checks.add("The website and the email differed by a letter: used " + agreed + " for both.");
                website = agreed;
            }
            confidence.put("emails", Math.min(confidence.getOrDefault("emails", 1.0), 0.75));
            confidence.put("website", Math.min(confidence.getOrDefault("website", 1.0), 0.75));
        }
        CardRules.NameCheck nc = CardRules.checkName(fullName, emails);
        if (nc.fixed()) { checks.add("Fixed the name's spelling from the email: " + fullName + " → " + nc.name() + "."); fullName = nc.name(); }
        if (nc.mismatch() != null) {
            checks.add("The name and the email spell differently (" + nc.mismatch() + "). Check both against the card.");
            confidence.put("fullName", Math.min(confidence.getOrDefault("fullName", 1.0), 0.6));
            confidence.put("emails", Math.min(confidence.getOrDefault("emails", 1.0), 0.6));
        }
        List<String> phones = new ArrayList<>();
        for (Object p : list(card.get("phones"))) {
            String raw = String.valueOf(p).trim(), v = CardRules.repairPhone(raw);
            if (v.replaceAll("\\D", "").length() < 8) continue;
            if (!v.equals(raw)) checks.add("Fixed letters read as digits in a phone number: " + raw + " → " + v + ".");
            if (!CardRules.plausibleIndianPhone(v)) {
                checks.add(v + " does not look like a valid Indian number. Check it.");
                confidence.put("phones", Math.min(confidence.getOrDefault("phones", 1.0), 0.5));
            }
            String ten = v.replaceAll("\\D", "");
            if (phones.stream().noneMatch(x -> x.replaceAll("\\D", "").endsWith(ten.length() > 10 ? ten.substring(ten.length() - 10) : ten))) phones.add(v);
        }
        if (website == null && !emails.isEmpty() && !freeMail(emails.get(0))) website = emails.get(0).substring(emails.get(0).indexOf('@') + 1);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("fullName", fullName);
        c.put("designation", card.get("designation"));
        c.put("department", department(null, (String) card.get("designation")));
        c.put("company", company);
        c.put("emails", emails);
        c.put("phones", phones);
        c.put("website", website);
        c.put("linkedin", card.get("linkedin"));
        c.put("city", card.get("city"));
        out.put("card", c);
        Map<String, Object> conf = new LinkedHashMap<>();
        confidence.forEach((k, v) -> conf.put(k, Math.round(v * 100) / 100.0));
        out.put("confidence", conf);
        out.put("source", source);
        Map<String, Object> m = matches(userId, emails, phones, website, company);
        out.put("matches", m);
        out.put("suggestions", suggestions(company, (String) card.get("designation"), m.get("company") != null));
        out.put("checks", checks);
        return out;
    }

    record Read(Map<String, Object> fields, Map<String, Double> confidence) {}

    private Read read(byte[] image, String filename, String type) {
        SarvamClient.Extraction x = sarvam.extract(image, filename == null ? "card" : filename, type, SCHEMA);
        JsonNode r = x.result();
        Map<String, Object> f = new LinkedHashMap<>();
        for (String k : List.of("fullName", "designation", "company", "website", "linkedin", "city")) { String v = text(r, k); if (v != null) f.put(k, v); }
        List<String> emails = new ArrayList<>();
        r.path("emails").forEach(e -> { String v = e.asText("").trim(); if (!v.isEmpty()) emails.add(v); });
        if (!emails.isEmpty()) f.put("emails", emails);
        List<String> phones = new ArrayList<>();
        r.path("phones").forEach(p -> { String v = p.asText("").trim(); if (!v.isEmpty()) phones.add(v); });
        if (!phones.isEmpty()) f.put("phones", phones);
        Map<String, Double> conf = new LinkedHashMap<>();
        x.annotations().fields().forEachRemaining(e -> {
            JsonNode a = e.getValue();
            conf.put(e.getKey(), a.isArray() ? min(a) : a.path("confidence").asDouble(1));
        });
        return new Read(f, conf);
    }

    private static boolean present(Object v) { return v != null && !(v instanceof List<?> l && l.isEmpty()); }

    private static List<?> list(Object v) { return v instanceof List<?> l ? l : v == null ? List.of() : List.of(v); }

    /** Two reads say the same thing (ignoring case, spaces and punctuation; lists in any order). */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a instanceof List<?> la && b instanceof List<?> lb)
            return new HashSet<>(la.stream().map(x -> key(String.valueOf(x))).toList()).equals(new HashSet<>(lb.stream().map(x -> key(String.valueOf(x))).toList()));
        return key(String.valueOf(a)).equals(key(String.valueOf(b)));
    }

    private static String key(String v) { return v.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@]", ""); }

    private static String label(String field) {
        return switch (field) { case "fullName" -> "the name"; case "emails" -> "the email"; case "phones" -> "the phone"; default -> "the " + field; };
    }

    /**
     * "Northwnd Labs" is probably the "Northwind Labs" already in the CRM, and "Head - Human Resourses" the known title
     * "Head - Human Resources". Offered as one-tap suggestions, never applied silently.
     */
    Map<String, Object> suggestions(String company, String designation, boolean companyMatched) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (company != null && !companyMatched) {
            String k = CardRules.companyKey(company);
            if (k.length() >= 4) {
                Map<String, Object> best = null; double bestScore = 0;
                for (Map<String, Object> a : jdbc.queryForList("SELECT id, name FROM crm.account WHERE left(name_normalized, 1) = left(?, 1)", k)) {
                    double sc = CardRules.similarity(k, CardRules.companyKey((String) a.get("name")));
                    if (sc > bestScore) { bestScore = sc; best = a; }
                }
                if (best != null && bestScore >= 0.85 && bestScore < 1) s.put("company", Map.of("id", best.get("id"), "name", best.get("name")));
            }
        }
        if (designation != null) {
            String d = designation.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            String best = null; double bestScore = 0;
            for (String known : jdbc.queryForList("SELECT DISTINCT designation FROM crm.designation_map", String.class)) {
                double sc = CardRules.similarity(d, known.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim());
                if (sc > bestScore) { bestScore = sc; best = known; }
            }
            if (best != null && bestScore >= 0.88 && bestScore < 1 && d.length() >= 6) s.put("designation", best);
        }
        return s;
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

    /**
     * Drops emails that belong to someone else in the photo (another card on the desk): when the card has a website,
     * keep emails at that domain or with the person's name in them; if none qualify, keep what was read.
     */
    static List<String> ownEmails(List<String> emails, String website, String fullName) {
        if (emails.size() < 2 || website == null) return emails;
        String domain = website.toLowerCase(Locale.ROOT).replaceAll("^https?://", "").replaceAll("^www\\.", "").replaceAll("/.*$", "");
        List<String> names = fullName == null ? List.of() : Arrays.stream(fullName.toLowerCase(Locale.ROOT).split("\\s+")).filter(n -> n.length() >= 3).toList();
        List<String> own = emails.stream().filter(e -> e.endsWith("@" + domain) || e.endsWith("." + domain)
                || names.stream().anyMatch(n -> e.substring(0, e.indexOf('@')).contains(n))).toList();
        return own.isEmpty() ? emails : own;
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
