package com.prosperr.crm.importer.application;

import com.prosperr.crm.shared.domain.Department;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forgiving reading of the values people type in sheets: other spellings of cities ("Bangalore", "Gurgaon, Haryana"),
 * industries ("Fintech", "Information Technology"), lead sources ("Apollo.io", "Conference"), functions ("Human
 * Resources") and numbers ("1.2k", "501-1,000", "10,000+"). Each returns the CRM's own name, or the input unchanged.
 */
final class ImportValues {
    private ImportValues() {}

    private static final Map<String, String> CITY_ALIASES = Map.ofEntries(
            Map.entry("bangalore", "Bengaluru"), Map.entry("bengaluru", "Bengaluru"), Map.entry("blr", "Bengaluru"),
            Map.entry("gurgaon", "Gurugram"), Map.entry("gurugram", "Gurugram"), Map.entry("ggn", "Gurugram"),
            Map.entry("bombay", "Mumbai"), Map.entry("navi mumbai", "Mumbai"), Map.entry("thane", "Mumbai"),
            Map.entry("new delhi", "Delhi"), Map.entry("delhi ncr", "Delhi"), Map.entry("ncr", "Delhi"),
            Map.entry("madras", "Chennai"), Map.entry("calcutta", "Kolkata"), Map.entry("cochin", "Kochi"), Map.entry("ernakulam", "Kochi"),
            Map.entry("noida", "Noida"), Map.entry("greater noida", "Noida"), Map.entry("secunderabad", "Hyderabad"), Map.entry("hyd", "Hyderabad"),
            Map.entry("poona", "Pune"), Map.entry("pimpri chinchwad", "Pune"));

    /** "Gurgaon, Haryana, India" -> "Gurugram"; "BANGALORE" -> "Bengaluru". */
    static String city(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)", "").replaceAll("[.]", "").trim();
        String first = v.split("[,/|;-]")[0].trim();
        for (String c : new String[]{v, first}) if (CITY_ALIASES.containsKey(c)) return CITY_ALIASES.get(c);
        return first.isEmpty() ? raw.trim() : Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    private static final String[][] INDUSTRY = {
            {"SaaS / software", "saas|software|cloud"},
            {"IT services", "it services|information technology|\\bit\\b|consulting|outsourc|ites|bpo|kpo|technology services|digital services"},
            {"BFSI", "bfsi|bank|financ|fintech|insur|nbfc|lending|payments|wealth|broking|investment"},
            {"Healthcare", "health|hospital|pharma|medical|clinic|diagnost|biotech|life science|wellness"},
            {"Retail / e-commerce", "retail|e-?commerce|d2c|consumer|fmcg|marketplace|fashion|food|beverage"},
            {"Manufacturing", "manufactur|industrial|automotive|auto|engineering|chemical|steel|textile|electronics|energy|cement"},
            {"Education", "educat|edtech|school|universit|learning|training|college"},
            {"Logistics", "logistic|supply chain|shipping|transport|freight|courier|mobility|warehous"},
    };

    /** "Fintech" -> "BFSI", "Information Technology & Services" -> "IT services"; unknown stays as typed. */
    static String industry(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (String[] row : INDUSTRY) if (v.equals(row[0].toLowerCase(Locale.ROOT))) return row[0];
        for (String[] row : INDUSTRY) if (Pattern.compile(row[1]).matcher(v).find()) return row[0];
        return raw.trim();
    }

    /** "Apollo.io" -> "Apollo", "Conference" -> "Event", "Website form" -> "Inbound". */
    static String leadSource(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.contains("apollo")) return "Apollo";
        if (v.contains("linkedin") || v.contains("sales navigator")) return "LinkedIn";
        if (v.matches(".*(refer|referral|referred|partner).*")) return "Referral";
        if (v.matches(".*(event|conference|expo|summit|meetup|webinar|booth|qr|visiting card).*")) return "Event";
        if (v.matches(".*(inbound|website|web form|form|demo request|organic|google|ads?\\b|marketing).*")) return "Inbound";
        return raw.trim();
    }

    /** "Human Resources" -> HR, "Finance & Accounts" -> FINANCE, "Payroll ops" -> PAYROLL. */
    static Department department(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.toLowerCase(Locale.ROOT);
        if (v.contains("payroll")) return Department.PAYROLL;
        if (v.matches(".*\\b(hr|human|people|talent|c&b|compensation|benefits?|rewards?|chro)\\b.*")) return Department.HR;
        if (v.matches(".*\\b(finance|financial|accounts?|accounting|treasury|tax|cfo|controller)\\b.*")) return Department.FINANCE;
        try { return Department.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }   // the job title decides
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k|K|l|L|lakh|lac)?");

    /** "1,200" -> 1200, "1.2k" -> 1200, "501-1,000" -> 501 (the lower end), "10,000+" -> 10000. */
    static Integer number(String raw) {
        if (raw == null) return null;
        Matcher m = NUMBER.matcher(raw);
        if (!m.find()) return null;
        double n = Double.parseDouble(m.group(1).replace(",", ""));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        if (unit.equals("k")) n *= 1_000;
        else if (unit.startsWith("l")) n *= 100_000;
        return n > Integer.MAX_VALUE ? null : (int) Math.round(n);
    }
}
