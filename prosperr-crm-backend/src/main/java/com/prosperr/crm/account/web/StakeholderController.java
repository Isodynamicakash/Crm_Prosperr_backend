package com.prosperr.crm.account.web;

import com.prosperr.crm.account.api.StakeholderApi;
import com.prosperr.crm.account.api.StakeholderView;
import com.prosperr.crm.account.application.StakeholderService;
import com.prosperr.crm.shared.domain.EngagementStatus;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class StakeholderController {
    private final StakeholderService stakeholders;
    private final CurrentUser current;

    public StakeholderController(StakeholderService stakeholders, CurrentUser current) {
        this.stakeholders = stakeholders; this.current = current;
    }

    @GetMapping("/accounts/{accountId}/stakeholders")
    public List<StakeholderView> list(@PathVariable UUID accountId) { return stakeholders.forAccount(accountId, current.get()); }

    /** Manual entry or "by LinkedIn URL" (M1-1): only a name and linkedinUrl are needed. */
    @PostMapping("/accounts/{accountId}/stakeholders")
    public StakeholderView create(@PathVariable UUID accountId, @RequestBody StakeholderApi.CreateStakeholder req) {
        var cmd = new StakeholderApi.CreateStakeholder(accountId, req.firstName(), req.lastName(), req.fullName(),
                req.designation(), req.department(), req.buyingRole(), req.emails(), req.phones(), req.linkedinUrl(),
                req.referredById(), req.connectionType(), req.bdrOwnerId(), req.primarySpoc(), req.decisionMaker(),
                req.allowSharedPhone());
        return stakeholders.create(cmd, current.get());
    }

    @PatchMapping("/stakeholders/{id}")
    public StakeholderView update(@PathVariable UUID id, @RequestBody StakeholderService.StakeholderUpdate req) {
        return stakeholders.update(id, req, current.get());
    }

    public record EngagementChange(EngagementStatus status, Instant nextFollowUpAt) {}

    @PatchMapping("/stakeholders/{id}/engagement")
    public StakeholderView engagement(@PathVariable UUID id, @RequestBody EngagementChange req) {
        return stakeholders.changeEngagement(id, req.status(), req.nextFollowUpAt(), current.get());
    }

    public record NewEmail(String email, boolean primary) {}
    public record NewPhone(String phone, boolean primary, boolean whatsapp, boolean allowShared) {}

    @PostMapping("/stakeholders/{id}/emails")
    public StakeholderView addEmail(@PathVariable UUID id, @RequestBody NewEmail req) {
        return stakeholders.addEmail(id, req.email(), req.primary(), current.get());
    }

    @PostMapping("/stakeholders/{id}/phones")
    public StakeholderView addPhone(@PathVariable UUID id, @RequestBody NewPhone req) {
        return stakeholders.addPhone(id, req.phone(), req.primary(), req.whatsapp(), req.allowShared(), current.get());
    }

    /** M1-3: mark a number invalid without closing the stakeholder. */
    @PostMapping("/phones/{phoneId}/invalid")
    public void invalidPhone(@PathVariable UUID phoneId) { stakeholders.markPhoneInvalid(phoneId, current.get()); }

    public record Dnc(String reason) {}

    @PostMapping("/stakeholders/{id}/do-not-contact")
    public void dnc(@PathVariable UUID id, @RequestBody Dnc req) { stakeholders.setDoNotContact(id, req.reason(), current.get()); }

    /** DPDP Act deletion on request. */
    @DeleteMapping("/admin/stakeholders/{id}/personal-data")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void erase(@PathVariable UUID id) { stakeholders.erase(id, current.get()); }
}
