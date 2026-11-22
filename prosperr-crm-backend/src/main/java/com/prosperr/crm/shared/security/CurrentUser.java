package com.prosperr.crm.shared.security;

import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Reads the signed-in user from the app JWT (sub = user id, role claim). */
@Component
public class CurrentUser {
    public Actor get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof Jwt jwt)) throw new ForbiddenException("Not signed in");
        return new Actor(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }
}
