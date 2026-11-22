package com.prosperr.crm.pipeline.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.MeetingLevel;
import com.prosperr.crm.shared.domain.MeetingOutcome;
import com.prosperr.crm.shared.domain.MeetingStatus;

import java.time.Instant;

/** PRD M6-1..M6-3: outcome -> meeting status; a meeting whose time has passed needs an outcome. */
public final class MeetingRules {

    public MeetingStatus statusFor(MeetingOutcome outcome) {
        return switch (outcome) {
            case COMPLETED_QUALIFIED, COMPLETED_FOLLOW_UP, COMPLETED_NOT_INTERESTED, DISQUALIFIED -> MeetingStatus.COMPLETED;
            case NO_SHOW -> MeetingStatus.NO_SHOW;
            case RESCHEDULED -> MeetingStatus.RESCHEDULED;
        };
    }

    public boolean outcomeOverdue(MeetingStatus status, Instant scheduledStart, MeetingOutcome outcome, Instant now) {
        return outcome == null && (status == MeetingStatus.SCHEDULED || status == MeetingStatus.RESCHEDULED)
                && scheduledStart.isBefore(now);
    }

    public void checkCombined(MeetingLevel level, boolean alsoCompletesL2) {
        if (alsoCompletesL2 && level != MeetingLevel.L1) {
            throw new DomainException("COMBINED_ONLY_L1", "Only an L1 meeting can also complete L2");
        }
    }
}
