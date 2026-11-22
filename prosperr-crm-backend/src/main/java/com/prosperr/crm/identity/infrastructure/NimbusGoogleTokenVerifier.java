package com.prosperr.crm.identity.infrastructure;

import com.prosperr.crm.identity.application.GoogleTokenVerifier;
import com.prosperr.crm.shared.config.CrmProperties;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Verifies Google ID tokens against Google's public keys, issuer and our OAuth client id (audience). */
@Component
public class NimbusGoogleTokenVerifier implements GoogleTokenVerifier {
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private final NimbusJwtDecoder decoder;

    public NimbusGoogleTokenVerifier(CrmProperties props) {
        this.decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
        String clientId = props.auth().googleClientId();
        OAuth2TokenValidator<Jwt> issuerAndAudience = jwt -> {
            String iss = jwt.getClaimAsString("iss");
            boolean ok = ISSUERS.contains(iss) && jwt.getAudience() != null && jwt.getAudience().contains(clientId);
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer or audience", null));
        };
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuerAndAudience));
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        try {
            Jwt jwt = decoder.decode(idToken);
            Object verified = jwt.getClaims().get("email_verified");
            return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified)), jwt.getClaimAsString("hd"));
        } catch (JwtException e) {
            throw new ForbiddenException("Google sign-in failed: " + e.getMessage());
        }
    }
}
