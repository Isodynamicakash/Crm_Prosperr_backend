package com.prosperr.crm.integrations.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.outreach.api.MailGateway;
import com.prosperr.crm.shared.config.CrmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Sequence emails sent from each BDR's own connected Gmail (crm.mail.provider=gmail). Follow-ups reply inside the first
 * email's thread; the thread is read back to spot replies, bounces and opt-outs. With crm.mail.test-to-self every email
 * goes to the BDR's own inbox instead, so the flow can be tried safely on real client data.
 */
@Component
@ConditionalOnProperty(name = "crm.mail.provider", havingValue = "gmail")
public class GmailMailGateway implements MailGateway {
    static final String API = "https://gmail.googleapis.com/gmail/v1/users/me";
    static final Pattern OPT_OUT = Pattern.compile("\\b(unsubscribe|remove me|stop (emailing|sending)|do not (email|contact))\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern BOUNCER = Pattern.compile("mailer-daemon|postmaster|mail delivery (subsystem|system)", Pattern.CASE_INSENSITIVE);

    private final GoogleAccountService google;
    private final ObjectMapper json;
    private final boolean testToSelf;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GmailMailGateway(GoogleAccountService google, ObjectMapper json, CrmProperties props) {
        this.google = google; this.json = json;
        this.testToSelf = props.mail() != null && props.mail().testToSelf();
    }

    @Override
    public SentMail send(OutgoingMail m) {
        String token = token(m.senderUserId());
        String to = m.to(), subject = m.subject(), body = m.body();
        if (testToSelf && !m.to().equalsIgnoreCase(m.fromAddress())) {
            to = m.fromAddress();
            subject = "[Test: to " + m.to() + "] " + subject;
            body = "TEST MODE: this email would have gone to " + m.to() + ".\n\n" + body;
        }
        String inReplyTo = null;
        if (m.threadId() != null) {                       // reply in the same thread: same subject, linked to the last message
            JsonNode thread = get(token, "/threads/" + m.threadId() + "?format=metadata&metadataHeaders=Subject&metadataHeaders=Message-ID");
            JsonNode msgs = thread.path("messages");
            if (msgs.size() > 0) {
                String first = header(msgs.get(0), "Subject");
                if (first != null) subject = first.regionMatches(true, 0, "Re:", 0, 3) ? first : "Re: " + first;
                inReplyTo = header(msgs.get(msgs.size() - 1), "Message-ID");
            }
        }
        StringBuilder mime = new StringBuilder()
                .append("From: ").append(m.fromAddress()).append("\r\n")
                .append("To: ").append(to).append("\r\n")
                .append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        if (inReplyTo != null) mime.append("In-Reply-To: ").append(inReplyTo).append("\r\nReferences: ").append(inReplyTo).append("\r\n");
        // plain text plus a clean HTML version (paragraphs, bullet lists, links, quiet footer); mail apps show the best one
        String boundary = "prosperr-" + UUID.randomUUID();
        mime.append("MIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n")
                .append("--").append(boundary).append("\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                .append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n")
                .append("--").append(boundary).append("\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                .append(Base64.getMimeEncoder().encodeToString(html(body).getBytes(StandardCharsets.UTF_8))).append("\r\n")
                .append("--").append(boundary).append("--\r\n");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("raw", Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toString().getBytes(StandardCharsets.UTF_8)));
        if (m.threadId() != null) req.put("threadId", m.threadId());
        JsonNode sent = call(token, HttpRequest.newBuilder(URI.create(API + "/messages/send"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(write(req))));
        return new SentMail(sent.path("id").asText(), sent.path("threadId").asText());
    }

    @Override
    public ThreadState check(UUID senderUserId, String threadId) {
        Optional<String> token = google.accessToken(senderUserId);
        if (token.isEmpty() || threadId == null) return new ThreadState(false, false, false);
        String me = google.email(senderUserId).orElse("").toLowerCase(Locale.ROOT);
        JsonNode thread = get(token.get(), "/threads/" + threadId + "?format=metadata&metadataHeaders=From&metadataHeaders=Subject");
        boolean replied = false, bounced = false, optedOut = false;
        for (JsonNode msg : thread.path("messages")) {
            String from = Optional.ofNullable(header(msg, "From")).orElse("").toLowerCase(Locale.ROOT);
            if (from.contains(me) && !me.isEmpty()) continue;                 // our own sends
            String subject = Optional.ofNullable(header(msg, "Subject")).orElse("");
            if (BOUNCER.matcher(from).find() || subject.toLowerCase(Locale.ROOT).contains("delivery status notification")) bounced = true;
            else {
                replied = true;
                if (OPT_OUT.matcher(msg.path("snippet").asText("")).find()) optedOut = true;
            }
        }
        return new ThreadState(replied && !optedOut, bounced, optedOut);
    }

    // ------------------------------------------------------------------ helpers

    private String token(UUID userId) {
        return google.accessToken(userId).orElseThrow(() ->
                new IllegalStateException("Gmail is not connected for this BDR. Connect Google from the sidebar and try again."));
    }

    private JsonNode get(String token, String path) {
        return call(token, HttpRequest.newBuilder(URI.create(API + path)).GET());
    }

    private JsonNode call(String token, HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 403 && r.body().contains("insufficient")) {
                throw new IllegalStateException("Google did not allow sending email. Reconnect Google and tick the Gmail permission.");
            }
            if (r.statusCode() >= 300) throw new IllegalStateException("Gmail error " + r.statusCode() + ": " + shorten(r.body()));
            return json.readTree(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gmail request interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reach Gmail: " + e.getMessage());
        }
    }

    private static final Pattern URL = Pattern.compile("(https?://[^\\s<]+[^\\s<.,;:)])");

    /**
     * The plain-text email as simple, readable HTML: one paragraph per block, "•" lines as a real list, links clickable,
     * and anything after the "--" separator (the unsubscribe line) as a small grey footer. No images, no tracking.
     */
    static String html(String text) {
        String main = text, footer = null;
        int cut = text.indexOf("\n\n--\n");
        if (cut >= 0) { main = text.substring(0, cut); footer = text.substring(cut + 5).strip(); }
        StringBuilder b = new StringBuilder("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "font-size:14.5px;line-height:1.6;color:#1f2421;max-width:600px\">");
        for (String block : main.strip().split("\n\\s*\n")) {
            String[] lines = block.split("\n");
            StringBuilder para = new StringBuilder();
            StringBuilder list = new StringBuilder();
            for (String line : lines) {
                String l = line.strip();
                if (l.startsWith("•") || l.startsWith("- ")) {
                    list.append("<li style=\"margin:0 0 6px\">").append(inline(l.substring(1).strip())).append("</li>");
                } else {
                    if (list.length() > 0) { para.append(ul(list)); list.setLength(0); }
                    if (para.length() > 0 && !para.toString().endsWith(">")) para.append("<br>");
                    para.append(inline(l));
                }
            }
            if (list.length() > 0) para.append(ul(list));
            b.append("<p style=\"margin:0 0 14px\">").append(para).append("</p>");
        }
        if (footer != null) {
            b.append("<p style=\"margin:22px 0 0;padding-top:12px;border-top:1px solid #e3e7e4;font-size:12px;color:#7a837d\">")
             .append(inline(footer).replace("\n", "<br>")).append("</p>");
        }
        return b.append("</div>").toString();
    }

    private static String ul(StringBuilder items) {
        return "</p><ul style=\"margin:4px 0 14px;padding-left:20px\">" + items + "</ul><p style=\"margin:0\">";
    }

    private static String inline(String s) {
        String esc = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return URL.matcher(esc).replaceAll("<a href=\"$1\" style=\"color:#0E7A35;text-decoration:underline\">$1</a>");
    }

    static String header(JsonNode msg, String name) {
        for (JsonNode h : msg.path("payload").path("headers")) if (name.equalsIgnoreCase(h.path("name").asText())) return h.path("value").asText();
        return null;
    }

    /** RFC 2047 encoded-word so non-ASCII subjects (₹, names in Indian scripts) survive. */
    static String encodeHeader(String s) {
        boolean ascii = s.chars().allMatch(c -> c >= 32 && c < 127);
        return ascii ? s : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String shorten(String s) { return s.length() > 300 ? s.substring(0, 300) : s; }
}
