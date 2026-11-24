package com.prosperr.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groq's OpenAI-compatible chat API: answers in well under a second. Off when GROQ_API_KEY is not set. */
@Component
public class GroqClient {
    private final String apiKey;
    private final String configuredModel;
    private volatile String model;
    /** Best first; used when the configured model is not available to this key. */
    static final List<String> PREFERRED = List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "meta-llama/llama-4-maverick-17b-128e-instruct",
            "moonshotai/kimi-k2-instruct", "qwen/qwen3-32b", "meta-llama/llama-4-scout-17b-16e-instruct", "openai/gpt-oss-20b", "llama-3.1-8b-instant");
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GroqClient(@Value("${crm.ai.groq-api-key:}") String apiKey, @Value("${crm.ai.groq-model:llama-3.3-70b-versatile}") String model, ObjectMapper json) {
        this.apiKey = apiKey == null ? null : apiKey.strip(); this.configuredModel = model == null ? null : model.strip(); this.model = this.configuredModel; this.json = json;   // a stray space in .env must not break the header
    }

    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    /** messages: [{role: system|user|assistant, content}] */
    public String chat(List<Map<String, String>> messages, int maxTokens) {
        if (!enabled()) throw new DomainException("AI_DISABLED", "Groq is not set up on this server.");
        try {
            return call(messages, maxTokens);
        } catch (ModelMissing e) {
            String next = pickModel();
            if (next == null || next.equals(model)) throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
            org.slf4j.LoggerFactory.getLogger(GroqClient.class).warn("Groq model {} is not available to this key; using {}", model, next);
            model = next;
            return call(messages, maxTokens);
        }
    }

    public String model() { return model; }

    private static final class ModelMissing extends RuntimeException {}

    /** The best chat model this key can use, from Groq's model list. */
    String pickModel() {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/models")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) return null;
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            json.readTree(res.body()).path("data").forEach(m -> { if (m.path("active").asBoolean(true)) ids.add(m.path("id").asText()); });
            for (String p : PREFERRED) if (ids.contains(p)) return p;
            return ids.stream().filter(id -> !id.matches("(?i).*(whisper|tts|guard|playai|orpheus|prompt|compound|distil).*")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String call(List<Map<String, String>> messages, int maxTokens) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("max_tokens", maxTokens);
            body.put("messages", messages);
            if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low");   // a help answer needs little thinking: faster
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) throw new DomainException("AI_BUSY", "The assistant is busy. Try again in a few seconds.");
            if (res.statusCode() == 404 && res.body().contains("model_not_found")) throw new ModelMissing();
            if (res.statusCode() >= 400) {
                org.slf4j.LoggerFactory.getLogger(GroqClient.class).warn("Groq {}: {}", res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
                throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
            }
            JsonNode n = json.readTree(res.body());
            return n.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(GroqClient.class).warn("Groq call failed: {}", e.toString());
            throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
        }
    }
}
