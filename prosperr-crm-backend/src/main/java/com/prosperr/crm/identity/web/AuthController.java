package com.prosperr.crm.identity.web;

import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.identity.application.AuthService;
import com.prosperr.crm.identity.application.TokenService;
import com.prosperr.crm.identity.application.UserService;
import com.prosperr.crm.shared.config.CrmProperties;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.web.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final UserService users;
    private final CurrentUser current;
    private final CrmProperties props;

    public AuthController(AuthService auth, UserService users, CurrentUser current, CrmProperties props) {
        this.auth = auth; this.users = users; this.current = current; this.props = props;
    }

    public record GoogleLogin(@NotBlank String idToken) {}
    public record DevLogin(@NotBlank String email) {}

    @PostMapping("/google")
    public TokenService.Token google(@RequestBody @jakarta.validation.Valid GoogleLogin req) {
        return auth.signInWithGoogle(req.idToken());
    }

    @PostMapping("/dev-login")
    public TokenService.Token dev(@RequestBody @jakarta.validation.Valid DevLogin req) {
        if (!props.auth().devLoginEnabled()) throw new NotFoundException("Endpoint", "/api/auth/dev-login");
        return auth.devSignIn(req.email());
    }

    /** Who am I: the frontend uses the role to pick the home screen. */
    @GetMapping("/me")
    public UserView me() { return users.get(current.get().userId()); }
}
