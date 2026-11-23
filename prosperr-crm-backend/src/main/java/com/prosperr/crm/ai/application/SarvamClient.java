package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.config.CrmProperties;
import com.prosperr.crm.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Thin HTTP client for Sarvam AI (speech-to-text and chat completions). */
@Component
public class SarvamClient {
    private static final Logger log = LoggerFactory.getLogger(SarvamClient.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final CrmProperties.Ai cfg;

    public SarvamClient(ObjectMapper json, CrmProperties props) {
        this.json = json;
        this.cfg = props.ai() == null ? new CrmProperties.Ai(null, null, null, null) : props.ai();
    }

    public boolean enabled() { return cfg.sarvamApiKey() != null && !cfg.sarvamApiKey().isBlank(); }

    private String base() { return cfg.sarvamBaseUrl() == null || cfg.sarvamBaseUrl().isBlank() ? "https://api.sarvam.ai" : cfg.sarvamBaseUrl(); }
    private String sttModel() { return cfg.sttModel() == null || cfg.sttModel().isBlank() ? "saarika:v2.5" : cfg.sttModel(); }
    private String chatModel() { return cfg.chatModel() == null || cfg.chatModel().isBlank() ? "sarvam-105b" : cfg.chatModel(); }

    /** Transcribes a short WAV clip; Sarvam detects the language (Hindi, English, Hinglish and other Indian languages). */
    public String transcribe(byte[] wav) {
        String boundary = "----crm" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        try {
            part(body, boundary, "model", sttModel());
            part(body, boundary, "language_code", "unknown");
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(wav);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
        var req = HttpRequest.newBuilder(URI.create(base() + "/speech-to-text"))
                .timeout(Duration.ofSeconds(45))
                .header("api-subscription-key", cfg.sarvamApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return send(req).path("transcript").asText("").trim();
    }

    /** One chat completion at temperature 0; returns the assistant's text. */
    public String chat(String system, String user) {
        try {
            // sarvam-105b is a reasoning model; with reasoning on it can spend its whole budget thinking and return
            // no answer. Extraction needs none, and with it off a reply takes about a second.
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("model", chatModel());
            body.put("temperature", 0);
            body.put("max_tokens", 600);
            body.put("reasoning_effort", null);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            String payload = json.writeValueAsString(body);
            var req = HttpRequest.newBuilder(URI.create(base() + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("api-subscription-key", cfg.sarvamApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            return send(req).path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private JsonNode send(HttpRequest req) {
        if (!enabled()) throw new DomainException("AI_DISABLED", "AI is not set up on this server.");
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Sarvam {} returned {}: {}", req.uri().getPath(), res.statusCode(), abbreviate(res.body()));
                throw new DomainException("AI_UNAVAILABLE", "The AI service could not process this. Fill the form by hand.");
            }
            return json.readTree(res.body());
        } catch (IOException e) {
            log.warn("Sarvam {} failed: {}", req.uri().getPath(), e.toString());
            throw new DomainException("AI_UNAVAILABLE", "The AI service is not reachable. Fill the form by hand.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("AI_UNAVAILABLE", "The AI request was interrupted.");
        }
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String abbreviate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}
