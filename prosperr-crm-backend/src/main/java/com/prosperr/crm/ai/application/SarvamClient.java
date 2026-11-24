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
    private String sttModel() { return cfg.sttModel() == null || cfg.sttModel().isBlank() ? "saaras:v3" : cfg.sttModel(); }
    private String chatModel() { return cfg.chatModel() == null || cfg.chatModel().isBlank() ? "sarvam-105b" : cfg.chatModel(); }

    public record Transcript(String text, String language) {}

    /** Transcribes a short WAV clip; Sarvam detects the language (22 Indian languages, English, and mixes of them). */
    public String transcribe(byte[] wav) { return transcribeWithLanguage(wav).text(); }

    /** Sarvam's live endpoint takes up to 30 s, so longer notes are cut into 25 s pieces and the text joined. */
    public Transcript transcribeWithLanguage(byte[] wav) {
        List<byte[]> pieces = WavChunks.split(wav, 25);
        if (pieces.size() == 1) return transcribeOne(wav);
        StringBuilder text = new StringBuilder();
        String lang = null;
        for (byte[] p : pieces) {
            Transcript t = transcribeOne(p);
            if (lang == null) lang = t.language();
            if (!t.text().isBlank()) text.append(text.isEmpty() ? "" : " ").append(t.text());
        }
        return new Transcript(text.toString(), lang);
    }

    private Transcript transcribeOne(byte[] wav) {
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
        JsonNode res = send(req);
        String lang = res.path("language_code").asText(null);
        return new Transcript(res.path("transcript").asText("").trim(), lang == null || lang.isBlank() ? null : lang);
    }

    /** One chat completion at temperature 0; returns the assistant's text. */
    public String chat(String system, String user) { return chat(system, user, 600); }

    /** Languages Sarvam's voice (bulbul:v3) can speak. */
    public static final java.util.Set<String> VOICE_LANGUAGES = java.util.Set.of("en-IN", "hi-IN", "bn-IN", "ta-IN", "te-IN", "kn-IN",
            "ml-IN", "mr-IN", "gu-IN", "pa-IN", "od-IN");

    /** Text to speech with bulbul:v3; returns WAV bytes. Text over the model's limit is cut at the last sentence that fits. */
    public byte[] speak(String text, String languageCode) {
        String lang = VOICE_LANGUAGES.contains(languageCode) ? languageCode : "en-IN";
        String t = text.length() <= 2400 ? text : text.substring(0, Math.max(text.lastIndexOf('.', 2400), 1200) + 1);
        try {
            String payload = json.writeValueAsString(Map.of("text", t, "target_language_code", lang, "model", "bulbul:v3"));
            var req = HttpRequest.newBuilder(URI.create(base() + "/text-to-speech"))
                    .timeout(Duration.ofSeconds(45))
                    .header("api-subscription-key", cfg.sarvamApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            String b64 = send(req).path("audios").path(0).asText("");
            if (b64.isEmpty()) throw new DomainException("AI_UNAVAILABLE", "The voice could not be generated.");
            return java.util.Base64.getDecoder().decode(b64);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public String chat(String system, String user, int maxTokens) {
        try {
            // sarvam-105b is a reasoning model; with reasoning on it can spend its whole budget thinking and return
            // no answer. Extraction needs none, and with it off a reply takes about a second.
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("model", chatModel());
            body.put("temperature", 0);
            body.put("max_tokens", maxTokens);
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
            HttpResponse<byte[]> raw = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = raw.body();
            if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {          // gzip, sent even unasked
                try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) { bytes = in.readAllBytes(); }
            }
            final String text = new String(bytes, StandardCharsets.UTF_8);
            final int code = raw.statusCode();
            record Res(int statusCode, String body) {}
            Res res = new Res(code, text);
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


    public record Extraction(JsonNode result, JsonNode annotations) {}

    /**
     * Sarvam Vision field extraction from one image or PDF (Document AI "extract" job): create the job with a JSON
     * schema, poll its status, then read the result. A visiting card takes about 4 seconds. Results come gzip-compressed.
     */
    public Extraction extract(byte[] file, String filename, String contentType, String schemaJson) {
        String boundary = "----crm" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        try {
            part(body, boundary, "schema", schemaJson);
            part(body, boundary, "output_format", "json");
            part(body, boundary, "mode", "basic");
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_")
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(file);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
        JsonNode job = send(HttpRequest.newBuilder(URI.create(base() + "/doc-ai/v1/job/extract")).timeout(Duration.ofSeconds(45))
                .header("api-subscription-key", cfg.sarvamApiKey()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
        String id = job.path("job_id").asText("");
        if (id.isEmpty()) throw new DomainException("AI_UNAVAILABLE", "The card could not be read. Fill the form by hand.");
        long deadline = System.currentTimeMillis() + 60_000;
        String status = job.path("status").asText("pending");
        while (!java.util.Set.of("completed", "partially_completed", "failed", "rejected").contains(status)) {
            if (System.currentTimeMillis() > deadline) throw new DomainException("AI_UNAVAILABLE", "Reading the card took too long. Try again.");
            try { Thread.sleep(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DomainException("AI_UNAVAILABLE", "Interrupted"); }
            status = send(get("/doc-ai/v1/job/" + id + "/status")).path("status").asText("");
        }
        if (!status.startsWith("completed") && !status.equals("partially_completed"))
            throw new DomainException("AI_UNAVAILABLE", "The card could not be read (" + status + "). Try a clearer photo.");
        JsonNode res = send(get("/doc-ai/v1/job/" + id + "/results"));
        return new Extraction(res.path("result"), res.path("annotations"));
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("api-subscription-key", cfg.sarvamApiKey()).header("Accept-Encoding", "gzip").GET().build();
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String abbreviate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}
