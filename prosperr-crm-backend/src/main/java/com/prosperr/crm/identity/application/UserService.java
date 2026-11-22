package com.prosperr.crm.identity.application;

import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.identity.infrastructure.AppUser;
import com.prosperr.crm.identity.infrastructure.AppUserRepository;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-managed users: the admin creates each user and decides the role (no self sign-up).
 * Deactivating a user lists their accounts for bulk reassignment (PRD M5-5).
 */
@Service
public class UserService implements UserDirectory {
    private final AppUserRepository repo;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public UserService(AppUserRepository repo, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.repo = repo; this.jdbc = jdbc; this.clock = clock;
    }

    public record CreateUser(String email, String fullName, String phone, Role role, UUID managerId) {}
    public record UpdateUser(String fullName, String phone, Role role, UUID managerId) {}

    @Transactional
    public UserView create(CreateUser c) {
        String email = c.email() == null ? "" : c.email().trim().toLowerCase(Locale.ROOT);
        if (email.isBlank() || c.fullName() == null || c.fullName().isBlank() || c.role() == null) {
            throw new DomainException("USER_FIELDS", "Email, full name and role are required");
        }
        if (repo.existsByEmailIgnoreCase(email)) throw new DomainException("USER_EXISTS", "A user with this email exists");
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setFullName(c.fullName().trim());
        u.setPhone(c.phone());
        u.setRole(c.role());
        u.setManagerId(c.managerId());
        return view(repo.saveAndFlush(u));
    }

    @Transactional
    public UserView update(UUID id, UpdateUser c) {
        AppUser u = entity(id);
        if (c.fullName() != null) u.setFullName(c.fullName().trim());
        if (c.phone() != null) u.setPhone(c.phone());
        if (c.role() != null) u.setRole(c.role());
        if (c.managerId() != null) u.setManagerId(c.managerId());
        return view(u);
    }

    public record OwnedAccount(UUID accountId, String name, String ownership, String accountStatus, String stage) {}

    /** Deactivate and return every account the user owns, active and inactive, for reassignment. */
    @Transactional
    public List<OwnedAccount> deactivate(UUID id) {
        AppUser u = entity(id);
        u.setActive(false);
        u.setDeactivatedAt(Instant.now(clock));
        return ownedAccounts(id);
    }

    @Transactional
    public UserView activate(UUID id) {
        AppUser u = entity(id);
        u.setActive(true);
        u.setDeactivatedAt(null);
        return view(u);
    }

    public List<OwnedAccount> ownedAccounts(UUID userId) {
        return jdbc.query("""
                SELECT a.id, a.name, 'AE' AS ownership, a.account_status, a.lifecycle_stage
                  FROM crm.account a WHERE a.ae_owner_id = :u
                UNION ALL
                SELECT a.id, a.name, 'BDR', a.account_status, a.lifecycle_stage
                  FROM crm.account a JOIN crm.account_bdr ab ON ab.account_id = a.id WHERE ab.bdr_id = :u
                ORDER BY 2""", Map.of("u", userId),
                (rs, i) -> new OwnedAccount(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
    }

    public List<UserView> all() { return repo.findAllByOrderByFullName().stream().map(UserService::view).toList(); }

    @Override public Optional<UserView> find(UUID id) { return repo.findById(id).map(UserService::view); }
    @Override public UserView get(UUID id) { return view(entity(id)); }
    @Override public List<UserView> activeByRole(Role role) {
        return repo.findByRoleAndActiveTrueOrderByFullName(role).stream().map(UserService::view).toList();
    }
    @Override public Optional<UserView> findByEmail(String email) {
        return repo.findByEmailIgnoreCase(email).map(UserService::view);
    }

    AppUser entity(UUID id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("User", id)); }

    static UserView view(AppUser u) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(), u.getManagerId(),
                u.isActive(), u.getLastLoginAt());
    }
}
