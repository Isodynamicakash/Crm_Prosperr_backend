package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.EngagementStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PRD M7-4 / M7-5 and BRD Stage 4: engagement is judged across the whole account.
 * One Unresponsive SPOC never makes the account inactive; it only flags that multithreading is needed.
 */
public final class AccountEngagement {
    public static final Set<EngagementStatus> ENGAGED = EnumSet.of(
            EngagementStatus.CONNECTED, EngagementStatus.MEETING_SCHEDULED,
            EngagementStatus.MEETING_COMPLETED, EngagementStatus.FOLLOW_UP_PENDING);
    private static final List<Department> FUNCTIONS = List.of(Department.FINANCE, Department.HR, Department.PAYROLL);

    public record FunctionCoverage(Department function, boolean engaged, Integer bestLevel, int stakeholders) {}

    public record Assessment(Map<Department, FunctionCoverage> coverage, boolean anyEngaged, boolean anyInProgress,
                             boolean onlyJuniorEngaged, boolean needsMultithreading, int stakeholderCount) {}

    public Assessment assess(List<StakeholderSnapshot> stakeholders) {
        Map<Department, FunctionCoverage> coverage = new EnumMap<>(Department.class);
        for (Department f : FUNCTIONS) {
            List<StakeholderSnapshot> inF = stakeholders.stream().filter(s -> s.department() == f).toList();
            List<StakeholderSnapshot> engaged = inF.stream().filter(s -> ENGAGED.contains(s.status())).toList();
            Integer best = engaged.stream().map(StakeholderSnapshot::level).filter(l -> l != null)
                    .min(Integer::compare).orElse(null);
            coverage.put(f, new FunctionCoverage(f, !engaged.isEmpty(), best, inF.size()));
        }
        boolean anyEngaged = stakeholders.stream().anyMatch(s -> ENGAGED.contains(s.status()));
        boolean anyInProgress = stakeholders.stream().anyMatch(s ->
                s.status() == EngagementStatus.NOT_CONTACTED || s.status() == EngagementStatus.CONTACTED
                        || ENGAGED.contains(s.status()));
        boolean anySenior = stakeholders.stream().anyMatch(s -> ENGAGED.contains(s.status())
                && s.level() != null && s.level() <= 2);
        boolean onlyJunior = anyEngaged && !anySenior;
        long engagedFunctions = coverage.values().stream().filter(FunctionCoverage::engaged).count();
        boolean spocSilent = stakeholders.stream().anyMatch(s -> s.primarySpoc() && s.status() == EngagementStatus.UNRESPONSIVE);
        boolean needsMt = stakeholders.size() <= 1 || spocSilent || !anyEngaged || onlyJunior || engagedFunctions < 2;
        return new Assessment(coverage, anyEngaged, anyInProgress, onlyJunior, needsMt, stakeholders.size());
    }
}
