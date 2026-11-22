package com.prosperr.crm.shared.security;

import com.prosperr.crm.shared.domain.Role;

import java.util.UUID;

/** The signed-in user making a request. */
public record Actor(UUID userId, Role role) {
    public boolean isLeadership() { return role == Role.MANAGER || role == Role.ADMIN; }
}
