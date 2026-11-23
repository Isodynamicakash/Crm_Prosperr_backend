package com.prosperr.crm.identity.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.prosperr.crm.shared.config.CrmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stateless API secured by the CRM's own HS256 JWT. Role checks with @PreAuthorize; record-level
 * (ownership, prospecting zone) checks in AccountGuard.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, CrmProperties props) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> c.configurationSource(cors(props)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/auth/google", "/api/auth/dev-login").permitAll()
                // Google redirects the browser here without our token; the signed state parameter identifies the user.
                .requestMatchers(HttpMethod.GET, "/api/integrations/google/callback").permitAll()
                .requestMatchers("/public/**", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(roleConverter())));
        return http.build();
    }

    static JwtAuthenticationConverter roleConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return c;
    }

    @Bean
    SecretKey appJwtKey(CrmProperties props) {
        byte[] bytes = props.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("crm.auth.jwt-secret must be at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey appJwtKey) { return new NimbusJwtEncoder(new ImmutableSecret<>(appJwtKey)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey appJwtKey, ActiveUserTokenValidator activeUser) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(appJwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                org.springframework.security.oauth2.jwt.JwtValidators.createDefault(), activeUser));
        return d;
    }

    private static CorsConfigurationSource cors(CrmProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(props.app().frontendUrl()));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("Content-Disposition"));
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
