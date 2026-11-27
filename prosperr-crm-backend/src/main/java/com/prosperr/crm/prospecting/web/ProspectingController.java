package com.prosperr.crm.prospecting.web;

import com.prosperr.crm.prospecting.application.ActivityService;
import com.prosperr.crm.prospecting.application.CallService;
import com.prosperr.crm.prospecting.application.MyDayService;
import com.prosperr.crm.prospecting.application.TaskService;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.prospecting.domain.MyDayItem;
import com.prosperr.crm.prospecting.domain.OutcomePlan;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.TaskSource;
import com.prosperr.crm.shared.domain.TaskType;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ProspectingController {
    private final CallService calls;
    private final MyDayService myDay;
    private final TaskService tasks;
    private final ActivityService activities;
    private final CurrentUser current;

    public ProspectingController(CallService calls, MyDayService myDay, TaskService tasks, ActivityService activities,
                                 CurrentUser current) {
        this.calls = calls; this.myDay = myDay; this.tasks = tasks; this.activities = activities; this.current = current;
    }

    /** M2-1. Managers may look at another user's queue with ?userId=. */
    @GetMapping("/my-day")
    public List<MyDayItem> myDay(@RequestParam(required = false) UUID userId) {
        var actor = current.get();
        if (userId != null && !userId.equals(actor.userId()) && !actor.isLeadership()) throw new ForbiddenException("Not your queue");
        return myDay.forUser(userId == null ? actor.userId() : userId);
    }

    @GetMapping("/stakeholders/{id}/calls/preview")
    public OutcomePlan preview(@PathVariable UUID id, @RequestParam String outcome,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate followUpDate,
                               @RequestParam(required = false) String reason) {
        return calls.preview(id, outcome, followUpDate, reason);
    }

    /** M2-2 / M2-3 / M2-4: log the outcome; response carries the next queue item for "save and next". */
    @PostMapping("/stakeholders/{id}/calls")
    public CallService.LogCallResult log(@PathVariable UUID id, @RequestBody CallService.LogCall req) {
        return calls.log(id, req, current.get());
    }

    @PostMapping("/activities")
    public Map<String, UUID> activity(@RequestBody ActivityService.ManualActivity req) {
        return Map.of("id", activities.logManual(req, current.get()));
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestParam(required = false) UUID assigneeId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Instant dueBefore) {
        var actor = current.get();
        UUID who = assigneeId == null ? actor.userId() : assigneeId;
        if (!who.equals(actor.userId()) && !actor.isLeadership()) throw new ForbiddenException("Not your tasks");
        return tasks.list(who, status, dueBefore);
    }

    public record NewTask(UUID accountId, UUID stakeholderId, TaskType type, String title, Instant dueAt, UUID assigneeId) {}

    @PostMapping("/tasks")
    public Map<String, UUID> create(@RequestBody NewTask t) {
        var actor = current.get();
        UUID assignee = t.assigneeId() == null ? actor.userId() : t.assigneeId();
        if (!assignee.equals(actor.userId()) && !actor.isLeadership()) throw new ForbiddenException("Only managers assign tasks to others");
        if (t.accountId() == null) throw new DomainException("ACCOUNT_REQUIRED", "Pick the account this task is for");
        if (t.title() == null || t.title().isBlank()) throw new DomainException("TITLE_REQUIRED", "Say what needs doing");
        if (t.dueAt() == null || t.dueAt().isBefore(Instant.now().minusSeconds(300))) throw new DomainException("DUE_IN_PAST", "Pick a due time in the future");
        return Map.of("id", tasks.create(new TaskApi.NewTask(t.accountId(), t.stakeholderId(), null, null,
                t.type() == null ? TaskType.OTHER : t.type(), t.title(), t.dueAt(), assignee, TaskSource.MANUAL, null, actor.userId())));
    }

    @PostMapping("/tasks/{id}/complete")
    public void complete(@PathVariable UUID id) { tasks.complete(id, current.get()); }

    /** Any field left out stays as it is: due date, assignee, the task's text and its type. */
    public record Reschedule(Instant dueAt, UUID assigneeId, String title, TaskType type) {}

    @PatchMapping("/tasks/{id}")
    public void reschedule(@PathVariable UUID id, @RequestBody Reschedule r) {
        tasks.update(id, r.dueAt(), r.assigneeId(), r.title(), r.type(), current.get());
    }
}