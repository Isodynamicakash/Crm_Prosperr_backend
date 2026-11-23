package com.prosperr.crm.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Values from application.yml under "crm". */
@ConfigurationProperties(prefix = "crm")
public record CrmProperties(Auth auth, App app, Jobs jobs, Ai ai, Google google) {
    public record Auth(String jwtSecret, Duration tokenTtl, String googleClientId, boolean devLoginEnabled) {}
    public record App(String baseUrl, String frontendUrl) {}
    public record Jobs(boolean enabled) {}
    /** Optional AI helpers; off when sarvamApiKey is blank. */
    public record Ai(String sarvamApiKey, String sarvamBaseUrl, String sttModel, String chatModel) {}
    /** Per-user Google connection for Calendar and Gmail; off when clientId is blank. */
    public record Google(String clientId, String clientSecret, String redirectUri, String encryptionKey, boolean inviteClients) {}
}
