package com.prosperr.crm.identity.web;

import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.identity.application.UserService;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Admin creates users and decides their role (PRD M10-1, Admin role). */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class UserAdminController {
    private final UserService users;
    private final CurrentUser current;
    private final com.prosperr.crm.identity.infrastructure.ActiveUserTokenValidator sessions;
    public UserAdminController(UserService users, CurrentUser current,
                               com.prosperr.crm.identity.infrastructure.ActiveUserTokenValidator sessions) {
        this.users = users; this.current = current; this.sessions = sessions;
    }

    @GetMapping
    public List<UserView> list() { return users.all(); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public UserView create(@RequestBody UserService.CreateUser req) { return users.create(req); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView update(@PathVariable UUID id, @RequestBody UserService.UpdateUser req) {
        var v = users.update(id, req);
        sessions.evict(id);
        return v;
    }

    /** Returns all accounts the user owns, for bulk reassignment (M5-5). */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserService.OwnedAccount> deactivate(@PathVariable UUID id) {
        var r = users.deactivate(id, current.get().userId());
        sessions.evict(id);
        return r;
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView activate(@PathVariable UUID id) {
        var v = users.activate(id);
        sessions.evict(id);
        return v;
    }

    @GetMapping("/{id}/accounts")
    public List<UserService.OwnedAccount> accounts(@PathVariable UUID id) { return users.ownedAccounts(id); }

    @GetMapping("/roles")
    public Role[] roles() { return Role.values(); }
}
