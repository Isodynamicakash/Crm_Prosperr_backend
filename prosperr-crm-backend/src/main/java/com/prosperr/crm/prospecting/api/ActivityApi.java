package com.prosperr.crm.prospecting.api;

import com.prosperr.crm.shared.domain.ActivityType;

import java.time.Instant;
import java.util.UUID;

/** Every call, email, WhatsApp, LinkedIn touch, visit or note on the account timeline. */
public interface ActivityApi {
    record NewActivity(UUID accountId, UUID stakeholderId, UUID opportunityId, UUID meetingId, UUID emailMessageId,
                       UUID phoneId, ActivityType type, UUID callOutcomeId, String remarks, Instant occurredAt, UUID loggedBy) {}

    UUID log(NewActivity a);
}
