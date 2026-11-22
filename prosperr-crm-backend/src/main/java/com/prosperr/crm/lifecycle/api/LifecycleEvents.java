package com.prosperr.crm.lifecycle.api;

import com.prosperr.crm.shared.domain.LifecycleStage;

import java.util.List;
import java.util.UUID;

public final class LifecycleEvents {
    private LifecycleEvents() {}

    public record AccountFlaggedForReview(UUID accountId, UUID aeOwnerId) {}
    public record AccountMovedInactive(UUID accountId, UUID aeOwnerId, String reason, UUID by) {}
    public record AccountReactivated(UUID accountId, UUID aeOwnerId, LifecycleStage returnedTo, UUID by) {}
    public record InactiveApprovalRequested(UUID accountId, UUID requestedBy, UUID managerId) {}
    public record RevisitDue(UUID accountId, List<UUID> bdrIds) {}
}
