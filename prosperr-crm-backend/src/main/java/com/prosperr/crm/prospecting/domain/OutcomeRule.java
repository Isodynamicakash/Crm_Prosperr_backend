package com.prosperr.crm.prospecting.domain;

import com.prosperr.crm.shared.domain.EngagementStatus;
import com.prosperr.crm.shared.domain.TaskType;

/** One row of crm.call_outcome_rule (PRD reference list "Call outcomes and their rules"). */
public record OutcomeRule(String outcomeCode, String label, EngagementStatus setsStatus, TaskType nextTaskType,
                          Integer nextTaskOffsetWorkingDays, boolean requiresDate, boolean requiresReason,
                          Integer maxAttempts, EngagementStatus afterMaxStatus, Integer revisitDays,
                          boolean offerSequence, boolean marksPhoneInvalid, boolean setsIcpFitNo,
                          boolean opensL1Booking, boolean createsReferral) {}
