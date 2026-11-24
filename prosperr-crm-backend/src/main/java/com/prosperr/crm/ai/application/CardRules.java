package com.prosperr.crm.ai.application;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks and repairs around the AI read of a visiting card. Each rule is deterministic and testable:
 * QR / vCard decoding (exact data, no OCR), repair of common OCR confusions in phones and emails, cross-checks
 * between fields that must agree (name vs email, email domain vs website) and fuzzy matching against known values.
 */
final class CardRules {
    private CardRules() {}

    static final Set<String> FREE_DOMAINS = Set.of("gmail.com", "yahoo.com", "yahoo.co.in", "hotmail.com", "outlook.com", "rediffmail.com",
            "icloud.com", "protonmail.com", "proton.me", "live.com", "ymail.com");

    // ------------------------------------------------------------------ QR codes

    /** Every QR code text in the photo (a card can carry a vCard QR and a website QR). Empty when none or unreadable. */
    static List<String> qrCodes(byte[] image) {
        List<String> out = new ArrayList<>();
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img == null) return out;
            Map<DecodeHintType, Object> hints = Map.of(DecodeHintType.TRY_HARDER, true,
                    DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
            // a blurred photo often decodes once shrunk (the blur shrinks with it); try a few sizes and both binarizers
            int side = Math.max(img.getWidth(), img.getHeight());
            outer:
            for (int max : new int[]{side, 1200, 800, 560, 400}) {
                if (max > side) continue;
                BufferedImage candidate = scaled(img, max);
                var source = new BufferedImageLuminanceSource(candidate);
                for (Binarizer bin : List.of(new HybridBinarizer(source), new com.google.zxing.common.GlobalHistogramBinarizer(source))) {
                    try {
                        for (Result r : new com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(new BinaryBitmap(bin), hints))
                            if (!out.contains(r.getText())) out.add(r.getText());
                    } catch (NotFoundException ignored) { /* next */ }
                    if (!out.isEmpty()) break outer;
                }
            }
        } catch (Exception ignored) { /* a broken image just means no QR */ }
        return out;
    }

    private static BufferedImage scaled(BufferedImage img, int max) {
        double s = Math.min(1, (double) max / Math.max(img.getWidth(), img.getHeight()));
        if (s >= 1) return img;
        BufferedImage o = new BufferedImage((int) (img.getWidth() * s), (int) (img.getHeight() * s), BufferedImage.TYPE_INT_RGB);
        var g = o.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, o.getWidth(), o.getHeight(), null);
        g.dispose();
        return o;
    }

    /**
     * Small photos (a WhatsApp forward, a far-away shot) are enlarged to about 1600px with bicubic interpolation and a
     * light sharpen before the AI reads them: small print gets more pixels per letter. Larger photos go as they are.
     */
    static byte[] prepare(byte[] image) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img == null) return image;
            int side = Math.max(img.getWidth(), img.getHeight());
            if (side >= 1000) return image;
            double s = 1600.0 / side;
            BufferedImage big = new BufferedImage((int) (img.getWidth() * s), (int) (img.getHeight() * s), BufferedImage.TYPE_INT_RGB);
            var g = big.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
            g.dispose();
            float[] k = {0, -0.25f, 0, -0.25f, 2f, -0.25f, 0, -0.25f, 0};                 // mild sharpen
            BufferedImage sharp = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(3, 3, k), java.awt.image.ConvolveOp.EDGE_NO_OP, null).filter(big, null);
            var bytes = new java.io.ByteArrayOutputStream();
            ImageIO.write(sharp, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception e) {
            return image;
        }
    }

    /** Fields from a QR: vCard (BEGIN:VCARD), MECARD, a LinkedIn link or a plain website link. */
    static Map<String, Object> parseQr(String text) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (text == null) return f;
        String t = text.trim();
        if (t.regionMatches(true, 0, "BEGIN:VCARD", 0, 11)) {
            List<String> emails = new ArrayList<>(), phones = new ArrayList<>();
            String unfolded = t.replaceAll("\r?\n[ \t]", "");
            for (String line : unfolded.split("\r?\n")) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String key = line.substring(0, c).toUpperCase(Locale.ROOT), v = unescape(line.substring(c + 1)).trim();
                String base = key.split(";")[0];
                if (base.contains(".")) base = base.substring(base.indexOf('.') + 1);   // item1.EMAIL
                if (v.isEmpty()) continue;
                switch (base) {
                    case "FN" -> f.put("fullName", v);
                    case "N" -> { if (!f.containsKey("fullName")) { String[] p = v.split(";", -1); f.put("fullName", ((p.length > 1 ? p[1] : "") + " " + p[0]).trim()); } }
                    case "ORG" -> f.put("company", v.split(";")[0].trim());
                    case "TITLE" -> f.put("designation", v);
                    case "EMAIL" -> emails.add(v.toLowerCase(Locale.ROOT));
                    case "TEL" -> phones.add(v.replaceFirst("(?i)^tel:", ""));
                    case "URL" -> { if (v.toLowerCase(Locale.ROOT).contains("linkedin.com/")) f.put("linkedin", v); else f.putIfAbsent("website", v); }
                    case "ADR" -> { String[] p = v.split(";", -1); if (p.length > 3 && !p[3].isBlank()) f.put("city", p[3].trim()); }
                    default -> { }
                }
            }
            if (!emails.isEmpty()) f.put("emails", emails);
            if (!phones.isEmpty()) f.put("phones", phones);
        } else if (t.regionMatches(true, 0, "MECARD:", 0, 7)) {
            List<String> emails = new ArrayList<>(), phones = new ArrayList<>();
            for (String part : t.substring(7).split("(?<!\\\\);")) {
                int c = part.indexOf(':');
                if (c < 0) continue;
                String k = part.substring(0, c).toUpperCase(Locale.ROOT), v = unescape(part.substring(c + 1)).trim();
                if (v.isEmpty()) continue;
                switch (k) {
                    case "N" -> { String[] p = v.split(","); f.put("fullName", (p.length > 1 ? p[1].trim() + " " : "") + p[0].trim()); }
                    case "ORG" -> f.put("company", v);
                    case "TITLE" -> f.put("designation", v);
                    case "EMAIL" -> emails.add(v.toLowerCase(Locale.ROOT));
                    case "TEL" -> phones.add(v);
                    case "URL" -> f.put("website", v);
                    default -> { }
                }
            }
            if (!emails.isEmpty()) f.put("emails", emails);
            if (!phones.isEmpty()) f.put("phones", phones);
        } else if (t.toLowerCase(Locale.ROOT).matches("^(https?://)?([a-z]+\\.)?linkedin\\.com/in/.*")) {
            f.put("linkedin", t);
        } else if (t.toLowerCase(Locale.ROOT).matches("^https?://[^\\s]+$")) {
            f.put("website", t);
        }
        return f;
    }

    private static String unescape(String v) {
        return v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\:", ":").replace("\\\\", "\\");
    }

    // ------------------------------------------------------------------ phones

    /** "+91 98I00 4S678" -> "+91 98100 45678": letters that OCR mixes up with digits, only inside a number. */
    static String repairPhone(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        long digits = v.chars().filter(Character::isDigit).count();
        if (digits < 6 || v.chars().filter(Character::isLetter).count() > 3) return v;   // not a number with a few misreads
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) {
            b.append(switch (c) { case 'O', 'o' -> '0'; case 'I', 'l', '|' -> '1'; case 'S', 's' -> '5'; case 'B' -> '8'; case 'Z', 'z' -> '2'; default -> c; });
        }
        return b.toString();
    }

    /** Indian numbers: a 10-digit mobile starts 6-9; landlines carry a 0 or 91 prefix. Other countries pass. */
    static boolean plausibleIndianPhone(String phone) {
        String d = phone.replaceAll("\\D", "");
        if (phone.trim().startsWith("+") && !d.startsWith("91")) return d.length() >= 8 && d.length() <= 15;
        if (d.startsWith("91") && d.length() == 12) d = d.substring(2);
        else if (d.startsWith("0") && d.length() == 11) return true;
        if (d.length() == 10) return d.charAt(0) >= '6' || d.startsWith("0");
        return d.length() >= 8 && d.length() <= 11;                       // landline with STD code, toll-free
    }

    // ------------------------------------------------------------------ emails

    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");

    /** "Neha . K (at) acme ,com" -> "neha.k@acme.com"; null when there is no email in it. */
    static String repairEmail(String raw) {
        if (raw == null) return null;
        String v = raw.toLowerCase(Locale.ROOT).trim()
                .replaceAll("\\s*[(\\[{]\\s*at\\s*[)\\]}]\\s*", "@").replaceAll("\\s*[(\\[{]\\s*dot\\s*[)\\]}]\\s*", ".")
                .replaceAll("\\s+", "").replace(",", ".").replace("@@", "@").replaceAll("\\.{2,}", ".")
                .replaceAll("^mailto:", "").replaceAll("[.;:]+$", "");
        Matcher m = EMAIL.matcher(v);
        if (!m.find()) return null;
        String e = m.group();
        String domain = e.substring(e.indexOf('@') + 1);
        for (String free : FREE_DOMAINS) if (!domain.equals(free) && distance(domain, free) == 1) return e.substring(0, e.indexOf('@') + 1) + free;
        return e;
    }

    static String domainOf(String websiteOrEmail) {
        if (websiteOrEmail == null) return null;
        String v = websiteOrEmail.toLowerCase(Locale.ROOT).trim();
        if (v.contains("@")) v = v.substring(v.indexOf('@') + 1);
        v = v.replaceAll("^https?://", "").replaceAll("^www\\.", "").replaceAll("[/?#].*$", "");
        return v.isEmpty() ? null : v;
    }

    /**
     * The card's email domain and website are printed separately; a 1-2 letter difference means one was misread.
     * Keep the spelling closer to the company name. Returns the agreed domain, or null when they are not near each other.
     */
    static String agreeDomain(String emailDomain, String webDomain, String company) {
        if (emailDomain == null || webDomain == null || emailDomain.equals(webDomain)) return null;
        if (FREE_DOMAINS.contains(emailDomain)) return null;
        int d = distance(emailDomain, webDomain);
        if (d == 0 || d > 2 || Math.min(emailDomain.length(), webDomain.length()) < 6) return null;
        String slug = company == null ? "" : company.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String a = emailDomain.split("\\.")[0], b = webDomain.split("\\.")[0];
        return slug.isEmpty() || similarity(b, slug) >= similarity(a, slug) ? webDomain : emailDomain;
    }

    // ------------------------------------------------------------------ name vs email

    record NameCheck(String name, boolean fixed, String mismatch) {}

    private static final String[][] OCR_SWAPS = {{"rn", "m"}, {"m", "rn"}, {"cl", "d"}, {"vv", "w"}, {"0", "o"}, {"1", "l"}, {"ii", "u"}, {"li", "h"}};

    /**
     * Most work emails contain the person's name. When a name word and an email word differ only by a classic OCR
     * confusion ("Sharrna" vs "sharma"), the name is corrected; any other near-miss is reported for the BDR to check.
     */
    static NameCheck checkName(String fullName, List<String> emails) {
        if (fullName == null || emails.isEmpty()) return new NameCheck(fullName, false, null);
        Set<String> local = new LinkedHashSet<>();
        for (String e : emails) for (String p : e.substring(0, e.indexOf('@')).split("[._\\-+0-9]+")) if (p.length() >= 3) local.add(p);
        String[] words = fullName.trim().split("\\s+");
        boolean fixed = false;
        String mismatch = null;
        for (int i = 0; i < words.length; i++) {
            String w = words[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (w.length() < 4 || local.contains(w)) continue;
            for (String l : local) {
                if (l.length() < 4 || l.charAt(0) != w.charAt(0)) continue;
                int d = distance(w, l);
                if (d == 0 || d > 2) continue;
                if (swapEquals(w, l)) {
                    words[i] = Character.toUpperCase(l.charAt(0)) + l.substring(1);
                    fixed = true;
                } else if (mismatch == null) {
                    mismatch = words[i] + " / " + l;
                }
                break;
            }
        }
        return new NameCheck(String.join(" ", words), fixed, mismatch);
    }

    private static boolean swapEquals(String misread, String truth) {
        for (String[] s : OCR_SWAPS) {
            int at = misread.indexOf(s[0]);
            while (at >= 0) {
                if ((misread.substring(0, at) + s[1] + misread.substring(at + s[0].length())).equals(truth)) return true;
                at = misread.indexOf(s[0], at + 1);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ similarity

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 1.0 identical, 0.0 nothing alike (Levenshtein ratio). */
    static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        int n = Math.max(a.length(), b.length());
        return n == 0 ? 1 : 1.0 - (double) distance(a, b) / n;
    }

    /** "Northwind Labs Pvt. Ltd." -> "northwind labs": the form company names are compared in. */
    static String companyKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("&", " and ")
                .replaceAll("\\b(pvt|private|ltd|limited|llp|inc|co|corp|india)\\b\\.?", " ").replaceAll("[^a-z0-9]+", " ").trim();
    }
}
