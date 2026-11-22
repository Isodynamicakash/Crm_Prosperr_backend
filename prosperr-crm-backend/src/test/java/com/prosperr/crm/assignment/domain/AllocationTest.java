package com.prosperr.crm.assignment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** BRD 7.2 example: AE A has 25, B has 12, C has 17 active opportunities. */
class AllocationTest {
    static AeLoad ae(String n, double load, int l1, Instant last) {
        return new AeLoad(UUID.randomUUID(), n, (int) load, l1, 0, 0, 0, 0, (int) load, load, last);
    }

    final Instant t = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void BRD_7_2_recommendsLeastLoaded() {
        var a = ae("A", 25, 2, t); var b = ae("B", 12, 2, t); var c = ae("C", 17, 2, t);
        var ranked = new CapacityAllocation().rank(List.of(a, b, c));
        assertEquals(List.of("B", "C", "A"), ranked.stream().map(AeLoad::name).toList());
        assertEquals(Workload.Level.HIGH, Workload.indicator(a, List.of(a, b, c)));
        assertEquals(Workload.Level.LOW, Workload.indicator(b, List.of(a, b, c)));
    }

    @Test
    void M5_2_tieBreakPendingL1ThenRoundRobin() {
        var x = ae("X", 10, 3, t); var y = ae("Y", 10, 1, t);
        assertEquals("Y", new CapacityAllocation().rank(List.of(x, y)).get(0).name());
        var p = ae("P", 10, 1, t.plusSeconds(100)); var q = ae("Q", 10, 1, t);
        assertEquals("Q", new CapacityAllocation().rank(List.of(p, q)).get(0).name(), "assigned least recently");
    }

    @Test
    void BRD_stage2_roundRobin() {
        var never = ae("New", 30, 0, null); var old = ae("Old", 1, 0, t);
        assertEquals("New", new RoundRobinAllocation().rank(List.of(old, never)).get(0).name());
    }
}
