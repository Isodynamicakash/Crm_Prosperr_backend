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
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GroqClient(@Value("${crm.ai.groq-api-key:}") String apiKey, @Value("${crm.ai.groq-model:llama-3.3-70b-versatile}") String model, ObjectMapper json) {
        this.apiKey = apiKey; this.model = model; this.json = json;
    }

    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    /** messages: [{role: system|user|assistant, content}] */
    public String chat(List<Map<String, String>> messages, int maxTokens) {
        if (!enabled()) throw new DomainException("AI_DISABLED", "Groq is not set up on this server.");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("max_tokens", maxTokens);
            body.put("messages", messages);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429) throw new DomainException("AI_BUSY", "The assistant is busy. Try again in a few seconds.");
            if (res.statusCode() >= 400) throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
            JsonNode n = json.readTree(res.body());
            return n.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("AI_UNAVAILABLE", "The assistant could not answer right now.");
        }
    }
}
