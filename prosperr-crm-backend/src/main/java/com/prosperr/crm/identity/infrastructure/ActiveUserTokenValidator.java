package com.prosperr.crm.identity.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejects a still-unexpired token when the user has been deactivated or their role has changed since sign-in,
 * so access is cut within seconds instead of when the 12-hour token runs out. Looked up at most every 15 s per user.
 */
@Component
public class ActiveUserTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final long TTL_MS = 15_000;
    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Your access changed. Please sign in again.", null);

    record State(boolean active, String role, long at) {}

    private final JdbcTemplate jdbc;
    private final Map<UUID, State> cache = new ConcurrentHashMap<>();

    public ActiveUserTokenValidator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID id;
        try { id = UUID.fromString(jwt.getSubject()); } catch (RuntimeException e) { return OAuth2TokenValidatorResult.failure(REVOKED); }
        long now = System.currentTimeMillis();
        State s = cache.get(id);
        if (s == null || now - s.at() > TTL_MS) {
            s = jdbc.query("SELECT is_active, role FROM crm.app_user WHERE id = ?", rs -> rs.next()
                    ? new State(rs.getBoolean(1), rs.getString(2), now) : new State(false, null, now), id);
            cache.put(id, s);
        }
        boolean ok = s.active() && s.role() != null && s.role().equals(jwt.getClaimAsString("role"));
        return ok ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(REVOKED);
    }

    /** Called when an admin changes a user so the change applies on the very next request. */
    public void evict(UUID userId) { cache.remove(userId); }
}
