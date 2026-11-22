package com.prosperr.crm.config.web;

import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.config.application.ConfigAdminService;
import com.prosperr.crm.config.application.ConfigService;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ConfigController {
    private final ConfigService config;
    private final ConfigAdminService admin;
    private final CurrentUser current;

    public ConfigController(ConfigService config, ConfigAdminService admin, CurrentUser current) {
        this.config = config; this.admin = admin; this.current = current;
    }

    // ---- read for dropdowns (everyone) ----
    @GetMapping("/api/config/picklists/{key}")
    public List<ConfigApi.PicklistItem> picklist(@PathVariable String key) { return config.picklist(key); }

    @GetMapping("/api/config/cities")
    public List<ConfigApi.CityView> cities() { return config.cities(); }

    @GetMapping("/api/config/size-buckets")
    public List<ConfigApi.BucketRow> buckets() { return config.sizeBuckets(); }

    @GetMapping("/api/config/designations")
    public List<ConfigApi.DesignationRow> designations() { return config.designations(); }

    @GetMapping("/api/config/checklist-items")
    public List<ConfigApi.ChecklistItem> checklist() { return config.checklistItems(); }

    // ---- admin edits ----
    @PutMapping("/api/admin/picklists")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void upsertPicklist(@RequestBody ConfigAdminService.PicklistUpsert p) { admin.upsertPicklist(p); }

    @GetMapping("/api/admin/settings")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Map<String, Object>> settings() { return admin.settings(); }

    /** Body is the raw JSON value, e.g. 60, true or "prosperr.io". */
    @PutMapping(value = "/api/admin/settings/{key}", consumes = "application/json")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void setSetting(@PathVariable String key, @RequestBody String jsonValue) {
        admin.setSetting(key, jsonValue, current.get().userId());
    }

    @PutMapping("/api/admin/cities")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void city(@RequestBody ConfigAdminService.CityUpsert c) { admin.upsertCity(c); }

    @PutMapping("/api/admin/size-buckets")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public Map<String, Integer> buckets(@RequestBody List<ConfigAdminService.BucketBounds> b) {
        return Map.of("accountsUpdated", admin.updateBuckets(b));
    }

    @PutMapping("/api/admin/designations")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void designation(@RequestBody ConfigAdminService.DesignationUpsert d) { admin.upsertDesignation(d); }

    @GetMapping("/api/admin/designations/review")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Map<String, Object>> review() { return admin.unmappedDesignations(); }

    @GetMapping("/api/admin/icp-rules")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Map<String, Object>> icpRules() { return admin.icpRules(); }

    public record IcpRuleUpdate(String config, Boolean active) {}

    @PutMapping("/api/admin/icp-rules/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void icpRule(@PathVariable String code, @RequestBody IcpRuleUpdate u) {
        admin.updateIcpRule(code, u.config(), u.active(), current.get().userId());
    }

    @GetMapping("/api/admin/call-outcome-rules")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Map<String, Object>> outcomeRules() { return admin.callOutcomeRules(); }

    @PutMapping("/api/admin/call-outcome-rules/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void outcomeRule(@PathVariable String code, @RequestBody ConfigAdminService.OutcomeRuleUpdate u) {
        admin.updateCallOutcomeRule(code, u);
    }

    public record ThresholdUpdate(Integer inactivityFlagDays, Integer stagnationDays, Integer followUpIntervalDays) {}

    @GetMapping("/api/admin/stage-thresholds")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<ConfigApi.StageThreshold> thresholds() { return config.stageThresholds(); }

    @PutMapping("/api/admin/stage-thresholds/{stage}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void threshold(@PathVariable String stage, @RequestBody ThresholdUpdate u) {
        admin.updateStageThreshold(stage, u.inactivityFlagDays(), u.stagnationDays(), u.followUpIntervalDays());
    }

    @PutMapping("/api/admin/checklist-items")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void checklistItem(@RequestBody ConfigAdminService.ChecklistUpsert c) { admin.upsertChecklistItem(c); }

    @GetMapping("/api/admin/permissions")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Map<String, Object>> permissions() { return admin.permissions(); }

    @PutMapping("/api/admin/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public void permission(@RequestBody ConfigAdminService.PermissionOverride p) {
        admin.setPermission(p, current.get().userId());
    }
}
