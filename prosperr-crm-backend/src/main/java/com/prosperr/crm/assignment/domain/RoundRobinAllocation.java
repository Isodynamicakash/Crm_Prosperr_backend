package com.prosperr.crm.assignment.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** BRD Stage 2 round-robin: the AE assigned least recently comes first. */
public final class RoundRobinAllocation implements AllocationStrategy {
    public String name() { return "ROUND_ROBIN"; }

    public List<AeLoad> rank(List<AeLoad> loads) {
        return loads.stream().sorted(Comparator
                .comparing(AeLoad::lastAssignedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
                .thenComparing(AeLoad::name)).toList();
    }
}
