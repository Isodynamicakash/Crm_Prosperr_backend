package com.prosperr.crm.pipeline.web;

import com.prosperr.crm.pipeline.application.AttachmentService;
import com.prosperr.crm.pipeline.application.MeetingService;
import com.prosperr.crm.pipeline.application.OpportunityService;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.domain.Role;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PipelineController {
    private final MeetingService meetings;
    private final OpportunityService opportunities;
    private final AttachmentService attachments;
    private final CurrentUser current;

    public PipelineController(MeetingService meetings, OpportunityService opportunities, AttachmentService attachments,
                              CurrentUser current) {
        this.meetings = meetings; this.opportunities = opportunities; this.attachments = attachments; this.current = current;
    }

    @GetMapping("/accounts/{id}/meetings")
    public List<Map<String, Object>> meetings(@PathVariable UUID id) { return meetings.forAccount(id, current.get()); }

    @PostMapping("/accounts/{id}/meetings")
    public MeetingService.Meeting book(@PathVariable UUID id, @RequestBody MeetingService.BookRequest r) {
        return meetings.book(id, r, current.get());
    }

    @PostMapping("/meetings/{id}/outcome")
    public MeetingService.Meeting outcome(@PathVariable UUID id, @RequestBody MeetingService.OutcomeRequest r) {
        return meetings.recordOutcome(id, r, current.get());
    }

    @PostMapping("/meetings/{id}/reschedule")
    public MeetingService.Meeting reschedule(@PathVariable UUID id, @RequestBody MeetingService.RescheduleRequest r) {
        return meetings.reschedule(id, r, current.get());
    }

    /** M6-2: an AE sees their own; managers see all or filter by aeId. */
    @GetMapping("/meetings/overdue-outcomes")
    public List<Map<String, Object>> overdue(@RequestParam(required = false) UUID aeId) {
        var actor = current.get();
        UUID who = actor.isLeadership() ? aeId : (actor.role() == Role.AE ? actor.userId() : actor.userId());
        return meetings.overdueOutcomes(who);
    }

    @GetMapping("/opportunities/{id}")
    public Map<String, Object> opportunity(@PathVariable UUID id) { return opportunities.get(id, current.get()); }

    @PatchMapping("/opportunities/{id}")
    public OpportunityService.Opp update(@PathVariable UUID id, @RequestBody OpportunityService.OppUpdate u) {
        return opportunities.update(id, u, current.get());
    }

    @PostMapping("/opportunities/{id}/stage")
    public OpportunityService.Opp stage(@PathVariable UUID id, @RequestBody OpportunityService.StageMove m) {
        return opportunities.moveStage(id, m, current.get());
    }

    @GetMapping("/opportunities/{id}/checklist")
    public List<Map<String, Object>> checklist(@PathVariable UUID id) { return opportunities.checklist(id); }

    @PutMapping("/opportunities/{id}/checklist/{itemId}")
    public List<Map<String, Object>> tick(@PathVariable UUID id, @PathVariable UUID itemId, @RequestBody OpportunityService.Tick t) {
        return opportunities.tick(id, itemId, t, current.get());
    }

    @GetMapping("/accounts/{id}/attachments")
    public List<Map<String, Object>> attachments(@PathVariable UUID id) { return attachments.list(id, current.get()); }

    @PostMapping("/accounts/{id}/attachments")
    public Map<String, UUID> attach(@PathVariable UUID id, @RequestBody AttachmentService.NewAttachment a) {
        return Map.of("id", attachments.add(id, a, current.get()));
    }
}
