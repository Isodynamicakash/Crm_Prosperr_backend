package com.prosperr.crm.qualification.domain;

import com.prosperr.crm.shared.domain.IntentRating;
import com.prosperr.crm.shared.domain.LocationPriority;
import com.prosperr.crm.shared.domain.SizeBucket;

/**
 * Everything the ICP checks read. Levels are stakeholder seniority tiers L1–L5 (not meetings);
 * null level = unmapped designation.
 */
public record QualificationInput(
        Integer stakeholderLevel, boolean stakeholderEngaged, Integer referrerLevel, boolean referrerSameAccount,
        Integer licensePotential, SizeBucket sizeBucket,
        boolean smallException, String smallExceptionReason, boolean smallApproved,
        LocationPriority locationPriority, IntentRating intentRating, String intentReason, boolean intentConfirmed,
        boolean hasBudget, boolean hasAuthority, boolean hasNeed, boolean hasTimeline) {}
