package com.prosperr.crm.assignment.domain;

import java.util.List;

/** BRD 7.2 example (25 / 12 / 17): relative workload indicator against the team average. */
public final class Workload {
    public enum Level { LOW, BALANCED, HIGH }

    public static Level indicator(AeLoad ae, List<AeLoad> team) {
        double avg = team.stream().mapToDouble(AeLoad::weightedLoad).average().orElse(0);
        if (avg == 0) return Level.BALANCED;
        double ratio = ae.weightedLoad() / avg;
        if (ratio >= 1.25) return Level.HIGH;
        if (ratio <= 0.75) return Level.LOW;
        return Level.BALANCED;
    }

    private Workload() {}
}
