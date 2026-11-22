package com.prosperr.crm.identity.web;

import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.identity.application.UserService;
import com.prosperr.crm.shared.domain.Role;
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
    public UserAdminController(UserService users) { this.users = users; }

    @GetMapping
    public List<UserView> list() { return users.all(); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public UserView create(@RequestBody UserService.CreateUser req) { return users.create(req); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView update(@PathVariable UUID id, @RequestBody UserService.UpdateUser req) { return users.update(id, req); }

    /** Returns all accounts the user owns, for bulk reassignment (M5-5). */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserService.OwnedAccount> deactivate(@PathVariable UUID id) { return users.deactivate(id); }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView activate(@PathVariable UUID id) { return users.activate(id); }

    @GetMapping("/{id}/accounts")
    public List<UserService.OwnedAccount> accounts(@PathVariable UUID id) { return users.ownedAccounts(id); }

    @GetMapping("/roles")
    public Role[] roles() { return Role.values(); }
}
