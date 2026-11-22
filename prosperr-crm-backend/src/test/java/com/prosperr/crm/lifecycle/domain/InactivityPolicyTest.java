package com.prosperr.crm.lifecycle.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.LifecycleStage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InactivityPolicyTest {
    final InactivityPolicy p = new InactivityPolicy();
    final LocalDate today = LocalDate.of(2026, 11, 20);

    @Test
    void M8_1_flagAfter60DaysWithoutActivity() {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        assertTrue(p.shouldFlag(now.minusSeconds(60L * 86400), 60, now, false));
        assertFalse(p.shouldFlag(now.minusSeconds(59L * 86400), 60, now, false));
        assertFalse(p.shouldFlag(now.minusSeconds(90L * 86400), 60, now, true), "already in the queue");
        assertTrue(p.shouldFlag(now.minusSeconds(31L * 86400), 30, now, false), "threshold differs by stage");
    }

    @Test
    void M8_2_moveNeedsReasonAndRemark_keepNeedsNextStepAndDate() {
        assertThrows(DomainException.class, () -> p.checkMoveToInactive(false, false, false, "x", null, today));
        assertThrows(DomainException.class, () -> p.checkMoveToInactive(true, false, false, " ", null, today));
        p.checkMoveToInactive(true, false, false, "Budget frozen", null, today);
        assertThrows(DomainException.class, () -> p.checkKeepActive("", today.plusDays(3), today));
        assertThrows(DomainException.class, () -> p.checkKeepActive("Call CFO", null, today));
        p.checkKeepActive("Call CFO", today.plusDays(3), today);
    }

    @Test
    void M8_5_changeableReasonsNeedFutureRevisitDate() {
        assertThrows(DomainException.class, () -> p.checkMoveToInactive(true, true, false, "On hold", null, today));
        assertThrows(DomainException.class, () -> p.checkMoveToInactive(true, true, false, "On hold", today, today));
        p.checkMoveToInactive(true, true, false, "On hold till April", today.plusDays(120), today);
    }

    @Test
    void M8_7_reactivateToLastStageOrL1WithNewStakeholder() {
        assertEquals(LifecycleStage.L2, p.reactivationStage(LifecycleStage.L2, false));
        assertEquals(LifecycleStage.L1, p.reactivationStage(LifecycleStage.L3, true));
        assertEquals(LifecycleStage.PROSPECT, p.reactivationStage(LifecycleStage.PROSPECT, true));
    }
}
