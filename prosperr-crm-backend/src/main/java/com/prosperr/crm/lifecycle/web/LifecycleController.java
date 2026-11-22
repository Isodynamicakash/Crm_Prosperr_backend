package com.prosperr.crm.lifecycle.web;

import com.prosperr.crm.lifecycle.application.LifecycleService;
import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class LifecycleController {
    private final LifecycleService lifecycle;
    private final CurrentUser current;

    public LifecycleController(LifecycleService lifecycle, CurrentUser current) { this.lifecycle = lifecycle; this.current = current; }

    @GetMapping("/lifecycle/to-be-moved")
    public List<Map<String, Object>> toBeMoved(@RequestParam(required = false) UUID aeId) {
        var actor = current.get();
        return lifecycle.toBeMoved(actor.role() == Role.AE ? actor.userId() : aeId);
    }

    @PostMapping("/accounts/{id}/inactive")
    public Map<String, String> inactive(@PathVariable UUID id, @RequestBody LifecycleService.MoveInactive m) {
        return Map.of("status", lifecycle.moveToInactive(id, m, current.get()));
    }

    @PostMapping("/accounts/{id}/keep-active")
    public void keep(@PathVariable UUID id, @RequestBody LifecycleService.KeepActive k) { lifecycle.keepActive(id, k, current.get()); }

    @PostMapping("/accounts/{id}/reactivate")
    public Map<String, LifecycleStage> reactivate(@PathVariable UUID id, @RequestBody LifecycleService.Reactivate r) {
        return Map.of("stage", lifecycle.reactivate(id, r, current.get()));
    }

    @GetMapping("/lifecycle/revival-queue")
    public List<Map<String, Object>> revival(@RequestParam(required = false) UUID bdrId) {
        var actor = current.get();
        return lifecycle.revivalQueue(actor.role() == Role.BDR ? actor.userId() : bdrId);
    }

    @GetMapping("/accounts/{id}/status-history")
    public List<Map<String, Object>> history(@PathVariable UUID id) { return lifecycle.statusHistoryFor(id); }
}
