package com.prosperr.crm.assignment.web;

import com.prosperr.crm.account.api.AccountView;
import com.prosperr.crm.assignment.application.AssignmentService;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AssignmentController {
    private final AssignmentService assignment;
    private final CurrentUser current;

    public AssignmentController(AssignmentService assignment, CurrentUser current) {
        this.assignment = assignment; this.current = current;
    }

    @GetMapping("/assignment/queue")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public List<Map<String, Object>> queue() { return assignment.queue(); }

    /** AE allocation dashboard. BDRs view it; an AE sees only their own row (permission table "View AE capacity"). */
    @GetMapping("/assignment/capacity")
    public Map<String, Object> capacity(@RequestParam(required = false) String strategy) {
        var actor = current.get();
        Map<String, Object> out = assignment.capacity(strategy);
        if (actor.role() == Role.AE) {
            @SuppressWarnings("unchecked")
            List<AssignmentService.CapacityRow> rows = (List<AssignmentService.CapacityRow>) out.get("aes");
            return Map.of("aes", rows.stream().filter(r -> r.load().aeId().equals(actor.userId())).toList());
        }
        return out;
    }

    @PostMapping("/accounts/{id}/assign")
    public AccountView assign(@PathVariable UUID id, @RequestBody AssignmentService.Assign a) {
        return assignment.assign(id, a, current.get());
    }

    @GetMapping("/accounts/{id}/assignment-history")
    public List<Map<String, Object>> history(@PathVariable UUID id) {
        if (current.get().role() == Role.BDR) throw new ForbiddenException("Not available to BDRs");
        return assignment.historyFor(id);
    }

    @PostMapping("/stakeholders/{id}/reassign-bdr")
    public void reassignBdr(@PathVariable UUID id, @RequestBody AssignmentService.ReassignBdr r) {
        assignment.reassignBdr(id, r, current.get());
    }

    public record Bulk(UUID fromUserId, List<AssignmentService.BulkMove> moves) {}

    @PostMapping("/assignment/bulk")
    public Map<String, Integer> bulk(@RequestBody Bulk b) {
        return Map.of("moved", assignment.bulkReassign(b.fromUserId(), b.moves(), current.get()));
    }
}
