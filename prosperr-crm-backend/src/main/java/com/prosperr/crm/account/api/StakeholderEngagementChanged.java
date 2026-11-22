package com.prosperr.crm.account.api;

import com.prosperr.crm.shared.domain.EngagementStatus;

import java.util.UUID;

/** Published on every engagement change; the timeline logs it and Unresponsive prompts the AE (M7-3). */
public record StakeholderEngagementChanged(UUID accountId, UUID stakeholderId, EngagementStatus from,
                                           EngagementStatus to, UUID changedBy, UUID aeOwnerId, UUID bdrOwnerId) {}
