package com.prosperr.crm.prospecting.domain;

import com.prosperr.crm.shared.domain.EngagementStatus;
import com.prosperr.crm.shared.domain.TaskType;

import java.time.LocalDate;

/**
 * What logging an outcome will do. Shown to the BDR before save and editable (PRD M2-3):
 * new status (null = no change), the next task and its due date, revisit date, and UI prompts.
 */
public record OutcomePlan(EngagementStatus newStatus, int attemptsAfter, TaskType taskType, LocalDate taskDue,
                          LocalDate revisitOn, boolean offerSequence, boolean markPhoneInvalid, boolean setIcpFitNo,
                          boolean openL1Booking, boolean createReferral, boolean maxAttemptsReached) {

    public OutcomePlan withTask(TaskType type, LocalDate due) {
        return new OutcomePlan(newStatus, attemptsAfter, type, due, revisitOn, offerSequence, markPhoneInvalid,
                setIcpFitNo, openL1Booking, createReferral, maxAttemptsReached);
    }

    public OutcomePlan withStatus(EngagementStatus status) {
        return new OutcomePlan(status, attemptsAfter, taskType, taskDue, revisitOn, offerSequence, markPhoneInvalid,
                setIcpFitNo, openL1Booking, createReferral, maxAttemptsReached);
    }
}
