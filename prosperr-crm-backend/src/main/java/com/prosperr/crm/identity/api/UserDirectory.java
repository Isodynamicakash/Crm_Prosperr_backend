package com.prosperr.crm.identity.api;

import com.prosperr.crm.shared.domain.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to users for other modules. */
public interface UserDirectory {
    Optional<UserView> find(UUID id);
    UserView get(UUID id);
    List<UserView> activeByRole(Role role);
    Optional<UserView> findByEmail(String email);
}
