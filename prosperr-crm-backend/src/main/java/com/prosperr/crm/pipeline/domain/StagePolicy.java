package com.prosperr.crm.pipeline.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.MeetingLevel;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PRD M6-4, M6-10, M6-11 and meeting stage definitions.
 * <ul>
 *   <li>The stage follows the latest completed meeting (L1, L2, L3).</li>
 *   <li>A stage counts as done only when its exit step is logged: L1 "documents shared", L2 "commercials sent".
 *       So the opportunity can reach L2 only after L1's exit step, and L3 only after L2's.</li>
 *   <li>A meeting tagged L1 + L2 completes both stages.</li>
 *   <li>Negotiation, Won, Lost and Implementation are set by the AE; the stage never moves backwards automatically.</li>
 * </ul>
 */
public final class StagePolicy {

    public record CompletedMeeting(MeetingLevel level, boolean alsoCompletesL2) {}

    /** Manual moves the AE may make, from -> allowed targets. */
    private static final Map<LifecycleStage, Set<LifecycleStage>> MANUAL = Map.of(
            LifecycleStage.SQL, EnumSet.of(LifecycleStage.LOST),
            LifecycleStage.L1, EnumSet.of(LifecycleStage.MULTITHREADING, LifecycleStage.LOST),
            LifecycleStage.MULTITHREADING, EnumSet.of(LifecycleStage.LOST),
            LifecycleStage.L2, EnumSet.of(LifecycleStage.LOST),
            LifecycleStage.L3, EnumSet.of(LifecycleStage.NEGOTIATION, LifecycleStage.WON, LifecycleStage.LOST),
            LifecycleStage.NEGOTIATION, EnumSet.of(LifecycleStage.WON, LifecycleStage.LOST),
            LifecycleStage.WON, EnumSet.of(LifecycleStage.IMPLEMENTATION));

    /**
     * The stage implied by completed meetings and logged exit steps.
     * Returns the current stage when meetings do not justify moving forward.
     */
    public LifecycleStage derive(LifecycleStage current, List<CompletedMeeting> completed,
                                 boolean l1ExitDone, boolean l2ExitDone) {
        if (current.isClosed() || current == LifecycleStage.NEGOTIATION) return current;
        boolean l1 = completed.stream().anyMatch(m -> m.level() == MeetingLevel.L1);
        boolean l2 = completed.stream().anyMatch(m -> m.level() == MeetingLevel.L2 || m.alsoCompletesL2());
        boolean l3 = completed.stream().anyMatch(m -> m.level() == MeetingLevel.L3);

        LifecycleStage target = current;
        if (l1 || l2 || l3) target = max(target, LifecycleStage.L1);
        if (l2 && l1ExitDone) target = max(target, LifecycleStage.L2);
        if (l3 && l1ExitDone && l2ExitDone) target = max(target, LifecycleStage.L3);
        return target;
    }

    /** What is blocking the next automatic move, for the account page ("what is still open"). */
    public String blockedReason(List<CompletedMeeting> completed, boolean l1ExitDone, boolean l2ExitDone) {
        boolean l2 = completed.stream().anyMatch(m -> m.level() == MeetingLevel.L2 || m.alsoCompletesL2());
        boolean l3 = completed.stream().anyMatch(m -> m.level() == MeetingLevel.L3);
        if ((l2 || l3) && !l1ExitDone) return "L1 exit step 'documents shared' is not logged";
        if (l3 && !l2ExitDone) return "L2 exit step 'commercials sent' is not logged";
        return null;
    }

    public void checkManualMove(LifecycleStage from, LifecycleStage to) {
        Set<LifecycleStage> allowed = MANUAL.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new DomainException("STAGE_MOVE_NOT_ALLOWED", "Cannot move from " + from + " to " + to);
        }
    }

    private static LifecycleStage max(LifecycleStage a, LifecycleStage b) {
        if (a == LifecycleStage.MULTITHREADING && b == LifecycleStage.L1) return a;
        return a.rank() >= b.rank() ? a : b;
    }
}
