package com.prosperr.crm.lifecycle.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.LifecycleStage;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * PRD M8-1, M8-2, M8-5, M8-7.
 * Flag for review after N days (default 60, per stage) with no completed meeting or activity.
 * Moving to Inactive needs a reason and remark; reasons that can change need a future revisit date.
 * Keeping active needs a next step and date. Reactivation returns to the last stage, or L1 with a new stakeholder.
 */
public final class InactivityPolicy {

    public boolean shouldFlag(Instant lastTouch, int thresholdDays, Instant now, boolean alreadyFlagged) {
        if (alreadyFlagged || lastTouch == null) return false;
        return Duration.between(lastTouch, now).toDays() >= thresholdDays;
    }

    public void checkMoveToInactive(boolean reasonGiven, boolean reasonNeedsRevisit, boolean reasonNeedsRemark,
                                    String remark, LocalDate revisitOn, LocalDate today) {
        if (!reasonGiven) throw new DomainException("REASON_REQUIRED", "An inactive reason is required");
        if (remark == null || remark.isBlank()) {
            throw new DomainException("REMARK_REQUIRED", "A remark is required when moving to Inactive");
        }
        if (reasonNeedsRevisit && (revisitOn == null || !revisitOn.isAfter(today))) {
            throw new DomainException("REVISIT_REQUIRED", "This reason needs a future revisit date");
        }
    }

    public void checkKeepActive(String nextStep, LocalDate nextStepDue, LocalDate today) {
        if (nextStep == null || nextStep.isBlank() || nextStepDue == null) {
            throw new DomainException("NEXT_STEP_REQUIRED", "Keeping the account active needs a next step and date");
        }
        if (nextStepDue.isBefore(today)) throw new DomainException("DATE_IN_PAST", "Next step date is in the past");
    }

    public LifecycleStage reactivationStage(LifecycleStage stalledAt, boolean withNewStakeholder) {
        if (!withNewStakeholder) return stalledAt;
        return stalledAt.rank() >= LifecycleStage.L1.rank() && !stalledAt.isClosed() ? LifecycleStage.L1 : stalledAt;
    }
}
