package com.prosperr.crm.pipeline.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.MeetingLevel;
import com.prosperr.crm.shared.domain.MeetingOutcome;
import com.prosperr.crm.shared.domain.MeetingStatus;
import com.prosperr.crm.shared.domain.SizeBucket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StagePolicyTest {
    final StagePolicy p = new StagePolicy();
    static StagePolicy.CompletedMeeting m(MeetingLevel l) { return new StagePolicy.CompletedMeeting(l, false); }

    @Test
    void M6_4_stageFollowsLatestCompletedMeeting() {
        assertEquals(LifecycleStage.L1, p.derive(LifecycleStage.SQL, List.of(m(MeetingLevel.L1)), false, false));
        assertEquals(LifecycleStage.L2, p.derive(LifecycleStage.L1, List.of(m(MeetingLevel.L1), m(MeetingLevel.L2)), true, false));
        assertEquals(LifecycleStage.L3, p.derive(LifecycleStage.L2,
                List.of(m(MeetingLevel.L1), m(MeetingLevel.L2), m(MeetingLevel.L3)), true, true));
    }

    @Test
    void M6_10_stageDoneOnlyWhenExitStepLogged() {
        // L2 meeting completed, but L1 "documents shared" not logged -> stays at L1
        assertEquals(LifecycleStage.L1, p.derive(LifecycleStage.L1, List.of(m(MeetingLevel.L1), m(MeetingLevel.L2)), false, false));
        assertNotNull(p.blockedReason(List.of(m(MeetingLevel.L2)), false, false));
        // L3 meeting completed, but L2 "commercials sent" not logged -> stays at L2
        assertEquals(LifecycleStage.L2, p.derive(LifecycleStage.L2, List.of(m(MeetingLevel.L3)), true, false));
        assertTrue(p.blockedReason(List.of(m(MeetingLevel.L3)), true, false).contains("commercials"));
    }

    @Test
    void M6_11_combinedL1L2MeetingCompletesBoth() {
        var combined = new StagePolicy.CompletedMeeting(MeetingLevel.L1, true);
        assertEquals(LifecycleStage.L2, p.derive(LifecycleStage.SQL, List.of(combined), true, false));
        assertThrows(DomainException.class, () -> new MeetingRules().checkCombined(MeetingLevel.L2, true));
    }

    @Test
    void M6_4_neverMovesBackwardsOrOutOfClosedStages() {
        assertEquals(LifecycleStage.NEGOTIATION, p.derive(LifecycleStage.NEGOTIATION, List.of(m(MeetingLevel.L1)), true, true));
        assertEquals(LifecycleStage.WON, p.derive(LifecycleStage.WON, List.of(m(MeetingLevel.L3)), true, true));
        assertEquals(LifecycleStage.MULTITHREADING, p.derive(LifecycleStage.MULTITHREADING, List.of(m(MeetingLevel.L1)), true, false));
    }

    @Test
    void M6_4_manualMovesNegotiationWonLostImplementation() {
        p.checkManualMove(LifecycleStage.L3, LifecycleStage.NEGOTIATION);
        p.checkManualMove(LifecycleStage.NEGOTIATION, LifecycleStage.WON);
        p.checkManualMove(LifecycleStage.WON, LifecycleStage.IMPLEMENTATION);
        p.checkManualMove(LifecycleStage.L1, LifecycleStage.LOST);
        p.checkManualMove(LifecycleStage.L1, LifecycleStage.MULTITHREADING);
        assertThrows(DomainException.class, () -> p.checkManualMove(LifecycleStage.L1, LifecycleStage.WON));
        assertThrows(DomainException.class, () -> p.checkManualMove(LifecycleStage.LOST, LifecycleStage.L1));
    }

    @Test
    void M6_3_outcomeMapsToMeetingStatus_and_M6_2_overdue() {
        MeetingRules r = new MeetingRules();
        assertEquals(MeetingStatus.NO_SHOW, r.statusFor(MeetingOutcome.NO_SHOW));
        assertEquals(MeetingStatus.COMPLETED, r.statusFor(MeetingOutcome.COMPLETED_FOLLOW_UP));
        assertEquals(MeetingStatus.RESCHEDULED, r.statusFor(MeetingOutcome.RESCHEDULED));
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        assertTrue(r.outcomeOverdue(MeetingStatus.SCHEDULED, now.minusSeconds(3600), null, now));
        assertFalse(r.outcomeOverdue(MeetingStatus.SCHEDULED, now.plusSeconds(3600), null, now));
        assertFalse(r.outcomeOverdue(MeetingStatus.COMPLETED, now.minusSeconds(3600), MeetingOutcome.COMPLETED_QUALIFIED, now));
    }

    @Test
    void M6_7_stagnationReasonForLargeAtL1AfterNDays() {
        StagnationPolicy s = new StagnationPolicy();
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Instant entered = now.minusSeconds(40L * 86400);
        assertTrue(s.reasonRequired(SizeBucket.LARGE, LifecycleStage.L1, entered, 30, now, false));
        assertTrue(s.reasonRequired(SizeBucket.ENTERPRISE, LifecycleStage.L1, entered, 30, now, false));
        assertFalse(s.reasonRequired(SizeBucket.MEDIUM, LifecycleStage.L1, entered, 30, now, false));
        assertFalse(s.reasonRequired(SizeBucket.LARGE, LifecycleStage.L2, entered, 30, now, false));
        assertFalse(s.reasonRequired(SizeBucket.LARGE, LifecycleStage.L1, entered, 30, now, true));
    }
}
