package com.prosperr.crm.account.api;

import java.util.UUID;

/** A stakeholder was added; a referral is logged on the multithreading timeline. */
public record StakeholderAdded(UUID accountId, UUID stakeholderId, UUID referredById, UUID addedBy) {}
