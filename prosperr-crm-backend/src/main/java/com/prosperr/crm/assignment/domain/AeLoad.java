package com.prosperr.crm.assignment.domain;

import java.time.Instant;
import java.util.UUID;

/** One AE's current workload (BRD 7.1 / PRD AE allocation dashboard). */
public record AeLoad(UUID aeId, String name, int activeAccounts, int l1Pending, int l2, int l3, int negotiation,
                     int inactive, int totalAssigned, double weightedLoad, Instant lastAssignedAt) {}
