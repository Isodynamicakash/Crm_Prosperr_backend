package com.prosperr.crm.identity.infrastructure;

import com.prosperr.crm.shared.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    List<AppUser> findByRoleAndActiveTrueOrderByFullName(Role role);
    List<AppUser> findAllByOrderByFullName();
    boolean existsByEmailIgnoreCase(String email);
}
