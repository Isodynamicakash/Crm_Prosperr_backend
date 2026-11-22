package com.prosperr.crm.assignment.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * PRD M5-2 and decision #8: least weighted load first (active accounts, L2/L3/Negotiation weighted 1.5x),
 * then fewest pending L1s, then round-robin (whoever was assigned least recently).
 */
public final class CapacityAllocation implements AllocationStrategy {
    public String name() { return "CAPACITY"; }

    public List<AeLoad> rank(List<AeLoad> loads) {
        return loads.stream().sorted(Comparator
                .comparingDouble(AeLoad::weightedLoad)
                .thenComparingInt(AeLoad::l1Pending)
                .thenComparing(AeLoad::lastAssignedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())))
                .toList();
    }
}
