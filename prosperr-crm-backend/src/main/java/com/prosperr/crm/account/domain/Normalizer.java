package com.prosperr.crm.account.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the clean keys used for duplicate detection (PRD: accounts match on name and domain;
 * stakeholders on email, LinkedIn URL or normalized phone) and the sheet clean-up rules from the
 * migration section ("NA" to empty, strip LinkedIn parameters, split names).
 */
public final class Normalizer {
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NON_DIGIT = Pattern.compile("[^0-9]");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Normalizer() {}

    /** Empty, "NA", "N/A", "-", "#N/A" and "null" become empty. */
    public static Optional<String> clean(String raw) {
        if (raw == null) return Optional.empty();
        String s = SPACES.matcher(raw.trim()).replaceAll(" ");
        String u = s.toUpperCase(Locale.ROOT);
        if (s.isEmpty() || u.equals("NA") || u.equals("N/A") || u.equals("-") || u.equals("#N/A") || u.equals("NULL")) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /** Same rule as the generated column account.name_normalized: lower-case, trimmed, single spaces. */
    public static String companyName(String name) {
        return clean(name).map(s -> s.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("company name is required"));
    }

    /** "https://www.ABCtech.com/about?x=1" -> "abctech.com". Accepts bare domains too. */
    public static Optional<String> domain(String websiteOrDomain) {
        return clean(websiteOrDomain).map(s -> {
            String v = s.toLowerCase(Locale.ROOT);
            if (!v.contains("://")) v = "http://" + v;
            String host;
            try {
                host = URI.create(v.replace(" ", "")).getHost();
            } catch (IllegalArgumentException e) {
                host = null;
            }
            if (host == null) return null;
            if (host.startsWith("www.")) host = host.substring(4);
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        }).filter(h -> h != null && h.contains("."));
    }

    /** Domain part of an email, for joining a stakeholder to an account. */
    public static Optional<String> emailDomain(String email) {
        return email(email).map(e -> e.substring(e.indexOf('@') + 1));
    }

    public static Optional<String> email(String raw) {
        return clean(raw).map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> EMAIL.matcher(s).matches());
    }

    /**
     * Indian numbers to E.164. 10 digits -> +91XXXXXXXXXX; 0 + 10 digits -> +91...; 91 + 10 digits -> +91...;
     * numbers given with + keep their country code. Anything shorter than 8 digits is invalid.
     */
    public static Optional<String> phoneE164(String raw) {
        return clean(raw).map(s -> {
            boolean plus = s.startsWith("+");
            String digits = NON_DIGIT.matcher(s).replaceAll("");
            if (plus) return digits.length() >= 8 && digits.length() <= 15 ? "+" + digits : null;
            if (digits.length() == 10) return "+91" + digits;
            if (digits.length() == 11 && digits.startsWith("0")) return "+91" + digits.substring(1);
            if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
            if (digits.length() >= 8 && digits.length() <= 15 && digits.startsWith("00")) return "+" + digits.substring(2);
            return null;
        });
    }

    /** Canonical LinkedIn profile URL without tracking parameters: https://www.linkedin.com/in/slug */
    public static Optional<String> linkedIn(String raw) {
        return clean(raw).map(s -> {
            String v = s.trim();
            if (!v.contains("://")) v = "https://" + v;
            URI uri;
            try {
                uri = URI.create(v.replace(" ", ""));
            } catch (IllegalArgumentException e) {
                return null;
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.endsWith("linkedin.com")) return null;
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isEmpty()) return null;
            return "https://www.linkedin.com" + path.toLowerCase(Locale.ROOT);
        });
    }

    /** "Rita  Sharma" -> [Rita, Sharma]; "Rita" -> [Rita, null]; extra words go to the last name. */
    public static String[] splitName(String fullName) {
        String s = clean(fullName).orElseThrow(() -> new IllegalArgumentException("name is required"));
        int i = s.indexOf(' ');
        return i < 0 ? new String[]{s, null} : new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    /** Same normalization the designation map uses (generated column designation_normalized). */
    public static String designation(String raw) {
        return clean(raw).map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
    }
}
