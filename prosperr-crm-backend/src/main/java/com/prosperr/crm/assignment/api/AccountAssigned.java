package com.prosperr.crm.assignment.api;

import java.util.UUID;

/** New AE (and previous AE, on reassignment) are notified. */
public record AccountAssigned(UUID accountId, UUID newOwnerId, UUID previousOwnerId, String ownerType, UUID assignedBy) {}
