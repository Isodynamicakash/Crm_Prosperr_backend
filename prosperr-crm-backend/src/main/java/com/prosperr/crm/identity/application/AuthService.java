package com.prosperr.crm.identity.application;

import com.prosperr.crm.identity.infrastructure.AppUser;
import com.prosperr.crm.identity.infrastructure.AppUserRepository;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Sign-in rules: the Google account must be verified, on the company domain, and already created by an admin
 * as an active user. Anyone else gets "ask your admin for access".
 */
@Service
public class AuthService {
    private final GoogleTokenVerifier google;
    private final AppUserRepository users;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthService(GoogleTokenVerifier google, AppUserRepository users, TokenService tokens, JdbcTemplate jdbc, Clock clock) {
        this.google = google; this.users = users; this.tokens = tokens; this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional
    public TokenService.Token signInWithGoogle(String idToken) {
        GoogleTokenVerifier.GoogleIdentity id = google.verify(idToken);
        if (!id.emailVerified() || id.email() == null) throw new ForbiddenException("Google email is not verified");
        String email = id.email().toLowerCase(Locale.ROOT);
        String domain = allowedDomain();
        if (domain != null && !email.endsWith("@" + domain)) {
            throw new ForbiddenException("Use your @" + domain + " Google account");
        }
        AppUser u = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ForbiddenException("No CRM access for " + email + ". Ask your admin for access."));
        return complete(u, id.subject());
    }

    /** Local development only (crm.auth.dev-login-enabled=true): sign in by email without Google. */
    @Transactional
    public TokenService.Token devSignIn(String email) {
        AppUser u = users.findByEmailIgnoreCase(email).orElseThrow(() -> new ForbiddenException("Unknown user " + email));
        return complete(u, null);
    }

    private TokenService.Token complete(AppUser u, String googleSub) {
        if (!u.isActive()) throw new ForbiddenException("Your CRM access has been deactivated. Ask your admin.");
        if (googleSub != null) {
            if (u.getGoogleSub() != null && !u.getGoogleSub().equals(googleSub)) {
                throw new ForbiddenException("This email is linked to a different Google account");
            }
            u.setGoogleSub(googleSub);
        }
        u.setLastLoginAt(Instant.now(clock));
        return tokens.issue(UserService.view(u));
    }

    private String allowedDomain() {
        String v = jdbc.query("SELECT value #>> '{}' FROM crm.app_setting WHERE key = 'allowed_login_domain'",
                rs -> rs.next() ? rs.getString(1) : null);
        return v == null || v.isBlank() ? null : v.toLowerCase(Locale.ROOT);
    }
}
