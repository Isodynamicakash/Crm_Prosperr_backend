package com.prosperr.crm.shared.domain;

/**
 * Funnel position. Account uses all values; opportunity starts at SQL.
 * Order matters: {@link #rank()} is used to stop the stage moving backwards.
 */
public enum LifecycleStage {
    PROSPECT(0), SQL(1), L1(2), MULTITHREADING(3), L2(4), L3(5), NEGOTIATION(6),
    WON(7), LOST(7), IMPLEMENTATION(8);

    private final int rank;
    LifecycleStage(int rank) { this.rank = rank; }
    public int rank() { return rank; }
    public boolean isClosed() { return this == WON || this == LOST || this == IMPLEMENTATION; }
    public boolean isOpenPipeline() { return rank >= L1.rank && !isClosed(); }
}
