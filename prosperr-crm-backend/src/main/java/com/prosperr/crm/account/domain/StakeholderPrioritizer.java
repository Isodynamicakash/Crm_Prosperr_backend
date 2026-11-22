package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.EngagementStatus;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PRD M2-6 / M7-1 / M7-3: stakeholders in priority order (CFO, CHRO, Head of Finance, Head of HR, C&B,
 * Total Rewards, Payroll, others), and the next one to approach when the current one goes silent.
 */
public final class StakeholderPrioritizer {
    /** Statuses that mean "do not approach this person next". */
    private static final Set<EngagementStatus> NOT_APPROACHABLE = EnumSet.of(
            EngagementStatus.UNRESPONSIVE, EngagementStatus.NOT_RELEVANT, EngagementStatus.REFERRED,
            EngagementStatus.MEETING_SCHEDULED, EngagementStatus.MEETING_COMPLETED);

    public static final Comparator<StakeholderSnapshot> PRIORITY = Comparator
            .comparingInt(StakeholderSnapshot::priorityRank)
            .thenComparing(s -> s.level() == null ? 9 : s.level())
            .thenComparing(StakeholderSnapshot::name, Comparator.nullsLast(Comparator.naturalOrder()));

    public List<StakeholderSnapshot> ordered(List<StakeholderSnapshot> all) {
        return all.stream().sorted(PRIORITY).toList();
    }

    /** Highest-priority stakeholder who has not been ruled out and is not on do-not-contact. */
    public Optional<StakeholderSnapshot> suggestNext(List<StakeholderSnapshot> all) {
        return all.stream()
                .filter(s -> !s.doNotContact())
                .filter(s -> !NOT_APPROACHABLE.contains(s.status()))
                .sorted(PRIORITY)
                .findFirst();
    }
}
