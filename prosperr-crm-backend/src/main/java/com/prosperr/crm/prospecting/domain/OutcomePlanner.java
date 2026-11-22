package com.prosperr.crm.prospecting.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.EngagementStatus;
import com.prosperr.crm.shared.domain.TaskType;
import com.prosperr.crm.shared.domain.WorkingDays;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Applies a call-outcome rule (PRD M2-3, decision #7): e.g. Did Not Pick -> Contacted, call again in
 * 2 working days, after 6 tries Unresponsive with a 30-day revisit. Any outcome that reaches the person
 * resets the attempt counter.
 */
public final class OutcomePlanner {
    private static final Set<EngagementStatus> REACHED = EnumSet.of(EngagementStatus.CONNECTED,
            EngagementStatus.FOLLOW_UP_PENDING, EngagementStatus.MEETING_SCHEDULED, EngagementStatus.REFERRED,
            EngagementStatus.NOT_RELEVANT);

    private final WorkingDays workingDays;

    public OutcomePlanner(WorkingDays workingDays) { this.workingDays = workingDays; }

    public OutcomePlan plan(OutcomeRule rule, int attemptsSoFar, LocalDate today, LocalDate chosenDate, String reason) {
        if (rule.requiresDate() && chosenDate == null) {
            throw new DomainException("DATE_REQUIRED", "'" + rule.label() + "' needs a follow-up date");
        }
        if (rule.requiresDate() && chosenDate.isBefore(today)) {
            throw new DomainException("DATE_IN_PAST", "Follow-up date cannot be in the past");
        }
        if (rule.requiresReason() && (reason == null || reason.isBlank())) {
            throw new DomainException("REASON_REQUIRED", "'" + rule.label() + "' needs a reason");
        }

        boolean counted = rule.maxAttempts() != null;
        int attempts = counted ? attemptsSoFar + 1
                : (rule.setsStatus() != null && REACHED.contains(rule.setsStatus()) ? 0 : attemptsSoFar);
        EngagementStatus status = rule.setsStatus();
        TaskType task = rule.nextTaskType();
        LocalDate due = null;
        LocalDate revisit = null;
        boolean maxed = false;

        if (counted && attempts >= rule.maxAttempts()) {
            maxed = true;
            status = rule.afterMaxStatus();
            revisit = rule.revisitDays() == null ? null : today.plusDays(rule.revisitDays());
            task = revisit == null ? null : TaskType.CALL;
            due = revisit;
        } else if (rule.requiresDate()) {
            due = chosenDate;
        } else if (task != null && rule.nextTaskOffsetWorkingDays() != null) {
            due = workingDays.add(today, rule.nextTaskOffsetWorkingDays());
        }

        if (!counted && rule.revisitDays() != null) {
            // e.g. Not Interested -> revisit in 90 days
            revisit = today.plusDays(rule.revisitDays());
            if (task == null) { task = TaskType.CALL; due = revisit; }
        }
        if (task != null && due == null) due = today;

        return new OutcomePlan(status, attempts, task, due, revisit, rule.offerSequence() && !maxed,
                rule.marksPhoneInvalid(), rule.setsIcpFitNo(), rule.opensL1Booking(), rule.createsReferral(), maxed);
    }
}
