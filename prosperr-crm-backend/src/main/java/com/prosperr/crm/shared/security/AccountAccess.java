package com.prosperr.crm.shared.security;

import com.prosperr.crm.shared.domain.Zone;

import java.util.Set;
import java.util.UUID;

/** What the permission check needs to know about one account. */
public record AccountAccess(UUID accountId, Zone zone, UUID aeOwnerId, Set<UUID> bdrIds) {
    public boolean isBdrOn(UUID userId) { return bdrIds != null && bdrIds.contains(userId); }
    public boolean isAeOwner(UUID userId) { return aeOwnerId != null && aeOwnerId.equals(userId); }
}
