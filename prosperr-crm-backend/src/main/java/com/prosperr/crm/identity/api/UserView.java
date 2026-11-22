package com.prosperr.crm.identity.api;

import com.prosperr.crm.shared.domain.Role;

import java.time.Instant;
import java.util.UUID;

public record UserView(UUID id, String email, String fullName, String phone, Role role, UUID managerId,
                       boolean active, Instant lastLoginAt) {
    public String firstName() {
        int i = fullName.indexOf(' ');
        return i < 0 ? fullName : fullName.substring(0, i);
    }
}
