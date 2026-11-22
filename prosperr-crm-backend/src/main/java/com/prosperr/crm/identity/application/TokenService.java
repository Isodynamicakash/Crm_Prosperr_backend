package com.prosperr.crm.identity.application;

import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.shared.config.CrmProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/** Issues the CRM's own short-lived JWT after Google sign-in. The role comes from app_user, not from Google. */
@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final CrmProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, CrmProperties props, Clock clock) {
        this.encoder = encoder; this.props = props; this.clock = clock;
    }

    public record Token(String accessToken, Instant expiresAt, UserView user) {}

    public Token issue(UserView u) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(props.auth().tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("prosperr-crm").subject(u.id().toString()).issuedAt(now).expiresAt(exp)
                .claim("role", u.role().name()).claim("email", u.email()).claim("name", u.fullName())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Token(value, exp, u);
    }
}
