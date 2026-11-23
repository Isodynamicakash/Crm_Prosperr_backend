package com.prosperr.crm.integrations.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.shared.config.CrmProperties;
import com.prosperr.crm.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * "Connect Google": OAuth code flow with offline access, refresh tokens stored encrypted in crm.user_mailbox,
 * short-lived access tokens cached in memory. A revoked or expired grant marks the connection EXPIRED.
 */
@Service
public class GoogleAccountService {
    private static final Logger log = LoggerFactory.getLogger(GoogleAccountService.class);
    static final List<String> SCOPES = List.of("openid", "email",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.freebusy",
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/gmail.readonly");

    public record Status(boolean available, boolean connected, String email, String status, Instant connectedAt, String lastError) {}
    record Cached(String token, Instant expires) {}

    private final CrmProperties.Google cfg;
    private final CrmProperties.App app;
    private final TokenCipher cipher;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<UUID, Cached> tokens = new ConcurrentHashMap<>();

    public GoogleAccountService(CrmProperties props, TokenCipher cipher, NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.cfg = props.google(); this.app = props.app(); this.cipher = cipher; this.jdbc = jdbc; this.json = json;
    }

    public boolean available() {
        return cfg != null && cfg.clientId() != null && !cfg.clientId().isBlank() && cfg.clientSecret() != null
                && !cfg.clientSecret().isBlank() && cipher.ready();
    }

    public boolean inviteClients() { return cfg != null && cfg.inviteClients(); }

    /** Google consent URL. returnTo is where the browser lands afterwards; only our own front-end origins are accepted. */
    public String authUrl(UUID userId, String returnTo) {
        if (!available()) throw new DomainException("GOOGLE_NOT_SET_UP", "Google isn't set up on this server yet.");
        String back = safeReturn(returnTo);
        String payload = userId + "|" + back + "|" + Instant.now().plusSeconds(600).getEpochSecond();
        String state = b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(cipher.sign(payload));
        return "https://accounts.google.com/o/oauth2/v2/auth?" + form(Map.of(
                "client_id", cfg.clientId(), "redirect_uri", cfg.redirectUri(), "response_type", "code",
                "scope", String.join(" ", SCOPES), "access_type", "offline", "prompt", "consent",
                "include_granted_scopes", "true", "state", state));   // no login hint: people connect their own Gmail
    }

    public record CallbackResult(String returnTo, boolean ok, String message) {}

    /** Exchanges the code, stores the encrypted refresh token and tells the caller where to send the browser. */
    public CallbackResult callback(String code, String state, String error) {
        String[] parts = state == null ? new String[0] : state.split("\\.");
        if (parts.length != 2) return new CallbackResult(app.frontendUrl(), false, "invalid_state");
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        if (!cipher.verify(payload, Base64.getUrlDecoder().decode(parts[1]))) return new CallbackResult(app.frontendUrl(), false, "invalid_state");
        String[] p = payload.split("\\|");
        UUID userId = UUID.fromString(p[0]);
        String back = p[1];
        if (Instant.now().getEpochSecond() > Long.parseLong(p[2])) return new CallbackResult(back, false, "expired");
        if (error != null || code == null) return new CallbackResult(back, false, error == null ? "cancelled" : error);
        try {
            JsonNode t = post("https://oauth2.googleapis.com/token", Map.of("code", code, "client_id", cfg.clientId(),
                    "client_secret", cfg.clientSecret(), "redirect_uri", cfg.redirectUri(), "grant_type", "authorization_code"));
            String refresh = t.path("refresh_token").asText(null);
            String granted = t.path("scope").asText("");
            String email = idTokenEmail(t.path("id_token").asText(""));
            if (refresh == null || email == null) return new CallbackResult(back, false, "no_offline_access");
            if (!granted.contains("calendar.events")) return new CallbackResult(back, false, "calendar_not_allowed");
            UUID other = jdbc.queryForList("SELECT user_id FROM crm.user_mailbox WHERE lower(gmail_address) = lower(:e) AND user_id <> :u",
                    Map.of("e", email, "u", userId), UUID.class).stream().findFirst().orElse(null);
            if (other != null) return new CallbackResult(back, false, "account_in_use");
            jdbc.update("""
                    INSERT INTO crm.user_mailbox (user_id, gmail_address, refresh_token_encrypted, status, scopes, last_error, connected_at, updated_at)
                    VALUES (:u, :e, :t, 'CONNECTED', :s, NULL, now(), now())
                    ON CONFLICT (user_id) DO UPDATE SET gmail_address = :e, refresh_token_encrypted = :t, status = 'CONNECTED',
                      scopes = :s, last_error = NULL, connected_at = now(), updated_at = now()""",
                    new MapSqlParameterSource().addValue("u", userId).addValue("e", email).addValue("t", cipher.encrypt(refresh)).addValue("s", granted));
            tokens.put(userId, new Cached(t.path("access_token").asText(), Instant.now().plusSeconds(t.path("expires_in").asLong(3000) - 60)));
            return new CallbackResult(back, true, email);
        } catch (Exception e) {
            log.warn("Google connect failed for {}", userId, e);
            return new CallbackResult(back, false, "exchange_failed");
        }
    }

    public Status status(UUID userId) {
        return jdbc.query("SELECT gmail_address, status, connected_at, last_error FROM crm.user_mailbox WHERE user_id = :u",
                Map.of("u", userId), rs -> rs.next()
                        ? new Status(available(), "CONNECTED".equals(rs.getString(2)), rs.getString(1), rs.getString(2),
                                rs.getTimestamp(3).toInstant(), rs.getString(4))
                        : new Status(available(), false, null, null, null, null));
    }

    public boolean connected(UUID userId) {
        return available() && Boolean.TRUE.equals(jdbc.query("SELECT status = 'CONNECTED' FROM crm.user_mailbox WHERE user_id = :u",
                Map.of("u", userId), rs -> rs.next() && rs.getBoolean(1)));
    }

    public Optional<String> email(UUID userId) {
        return jdbc.queryForList("SELECT gmail_address FROM crm.user_mailbox WHERE user_id = :u AND status = 'CONNECTED'",
                Map.of("u", userId), String.class).stream().findFirst();
    }

    /** A valid access token, refreshed when needed. Empty when the user isn't connected or Google refused. */
    public Optional<String> accessToken(UUID userId) {
        if (!available()) return Optional.empty();
        Cached c = tokens.get(userId);
        if (c != null && c.expires().isAfter(Instant.now())) return Optional.of(c.token());
        byte[] enc = jdbc.query("SELECT refresh_token_encrypted FROM crm.user_mailbox WHERE user_id = :u AND status = 'CONNECTED'",
                Map.of("u", userId), rs -> rs.next() ? rs.getBytes(1) : null);
        if (enc == null) return Optional.empty();
        try {
            JsonNode t = post("https://oauth2.googleapis.com/token", Map.of("client_id", cfg.clientId(), "client_secret", cfg.clientSecret(),
                    "refresh_token", cipher.decrypt(enc), "grant_type", "refresh_token"));
            String at = t.path("access_token").asText(null);
            if (at == null) throw new IllegalStateException("no access token");
            tokens.put(userId, new Cached(at, Instant.now().plusSeconds(t.path("expires_in").asLong(3000) - 60)));
            return Optional.of(at);
        } catch (GoogleError e) {
            if (e.status == 400 || e.status == 401) markExpired(userId, "Google access expired or was removed. Reconnect Google.");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Google token refresh failed for {}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    void markExpired(UUID userId, String why) {
        tokens.remove(userId);
        jdbc.update("UPDATE crm.user_mailbox SET status = 'EXPIRED', last_error = :w, updated_at = now() WHERE user_id = :u",
                Map.of("w", why, "u", userId));
    }

    public void disconnect(UUID userId) {
        byte[] enc = jdbc.query("SELECT refresh_token_encrypted FROM crm.user_mailbox WHERE user_id = :u", Map.of("u", userId),
                rs -> rs.next() ? rs.getBytes(1) : null);
        if (enc != null && cipher.ready()) {
            try { post("https://oauth2.googleapis.com/revoke", Map.of("token", cipher.decrypt(enc))); }
            catch (Exception e) { log.info("Google revoke for {} failed (already revoked?): {}", userId, e.toString()); }
        }
        tokens.remove(userId);
        jdbc.update("DELETE FROM crm.user_mailbox WHERE user_id = :u", Map.of("u", userId));
    }

    // ------------------------------------------------------------------ HTTP helpers shared with CalendarService

    static class GoogleError extends RuntimeException {
        final int status;
        GoogleError(int status, String body) { super("Google " + status + ": " + (body.length() > 300 ? body.substring(0, 300) : body)); this.status = status; }
    }

    JsonNode post(String url, Map<String, String> form) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(form))).build();
        return read(http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    JsonNode call(String method, String url, String token, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return read(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private JsonNode read(HttpResponse<String> res) throws Exception {
        if (res.statusCode() / 100 != 2) throw new GoogleError(res.statusCode(), res.body());
        return res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
    }

    private String idTokenEmail(String idToken) throws Exception {
        String[] p = idToken.split("\\.");
        if (p.length < 2) return null;
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(p[1]));   // received directly from Google over TLS
        return claims.path("email_verified").asBoolean(false) ? claims.path("email").asText(null) : null;
    }

    private String safeReturn(String returnTo) {
        String fallback = app.frontendUrl();
        if (returnTo == null || returnTo.isBlank()) return fallback;
        try {
            URI u = URI.create(returnTo);
            String host = u.getHost() == null ? "" : u.getHost();
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.endsWith(".localhost");
            String origin = u.getScheme() + "://" + u.getAuthority();
            return local || origin.equalsIgnoreCase(fallback) ? origin : fallback;
        } catch (RuntimeException e) { return fallback; }
    }

    static String form(Map<String, String> m) {
        return m.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
