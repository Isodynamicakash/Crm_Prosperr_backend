package com.prosperr.crm.outreach.web;

import com.prosperr.crm.outreach.application.SequenceService;
import com.prosperr.crm.outreach.application.TemplateService;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.domain.StopReason;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class OutreachController {
    private final SequenceService sequences;
    private final TemplateService templates;
    private final CurrentUser current;

    public OutreachController(SequenceService sequences, TemplateService templates, CurrentUser current) {
        this.sequences = sequences; this.templates = templates; this.current = current;
    }

    public record Mailbox(String gmailAddress, Integer dailyCap) {}

    /** M3-1: register the BDR's Gmail (OAuth consent is done by the frontend / Gmail adapter). */
    @PutMapping("/api/mailbox")
    public void mailbox(@RequestBody Mailbox m) { sequences.connectMailbox(current.get().userId(), m.gmailAddress(), m.dailyCap()); }

    public record Start(List<UUID> stakeholderIds, UUID sequenceId, boolean startedFromCall) {}

    @PostMapping("/api/sequences/start")
    public List<SequenceService.StartResult> start(@RequestBody Start s) {
        return sequences.start(s.stakeholderIds(), s.sequenceId(), s.startedFromCall(), current.get());
    }

    @PostMapping("/api/sequences/enrollments/{id}/stop")
    public void stop(@PathVariable UUID id) { sequences.requireExists(id); sequences.stop(id, StopReason.MANUAL); }

    @PostMapping("/api/sequences/enrollments/{id}/pause")
    public void pause(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean pause) { sequences.pause(id, pause); }

    @GetMapping("/api/sequences/monitor")
    public Map<String, Object> monitor(@RequestParam(required = false) UUID ownerId) {
        var actor = current.get();
        return sequences.monitor(actor.role() == Role.BDR ? actor.userId() : ownerId);
    }

    /** The emails this person would get (rendered, with send times) before the BDR starts the sequence. */
    @GetMapping("/api/stakeholders/{id}/sequence/preview")
    public Map<String, Object> preview(@PathVariable UUID id, @RequestParam(required = false) UUID sequenceId) {
        return sequences.preview(id, sequenceId, current.get());
    }

    @GetMapping("/api/stakeholders/{id}/sequence")
    public Map<String, Object> forStakeholder(@PathVariable UUID id) { return sequences.stakeholderSequence(id); }

    @GetMapping("/api/reports/sequence-performance")
    public List<Map<String, Object>> performance() { return sequences.performance(); }

    @GetMapping("/api/admin/email-templates")
    public List<Map<String, Object>> templates() { return templates.templates(); }

    @GetMapping("/api/admin/email-snippets")
    public List<Map<String, Object>> snippets() { return templates.snippets(); }

    @PutMapping("/api/admin/email-templates/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void updateTemplate(@PathVariable String code, @RequestBody TemplateService.TemplateUpdate u) {
        templates.updateTemplate(code, u, current.get().userId());
    }

    public record Snippet(String content) {}

    @PutMapping("/api/admin/email-snippets/{code}/{variant}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void snippet(@PathVariable String code, @PathVariable String variant, @RequestBody Snippet s) {
        templates.upsertSnippet(code, variant, s.content());
    }

    @GetMapping("/public/unsubscribe/{enrollmentId}")
    public String unsubscribe(@PathVariable UUID enrollmentId) {
        return sequences.unsubscribe(enrollmentId) ? "You have been unsubscribed." : "Link not recognised.";
    }
}
