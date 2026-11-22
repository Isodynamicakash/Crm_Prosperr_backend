package com.prosperr.crm.pipeline.api;

import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.MeetingLevel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineApi {
    record BookMeeting(UUID accountId, UUID opportunityId, MeetingLevel level, boolean alsoCompletesL2, Instant scheduledStart,
                       Instant scheduledEnd, String meetingLink, List<UUID> attendeeStakeholderIds, UUID bookedBy, UUID aeId) {}

    UUID bookMeeting(BookMeeting cmd);
    /** Creates the primary opportunity at stage SQL (idempotent) and links the account's meetings to it. */
    UUID openOpportunity(UUID accountId, UUID actor);
    Optional<UUID> opportunityId(UUID accountId);
    void setMeetingAe(UUID accountId, UUID aeId);
    void cancelOpenMeetings(UUID accountId);
    /** Reactivation back to the last stage or to L1 (M8-7). */
    void setStage(UUID accountId, LifecycleStage target, UUID actor);
}
