package com.prosperr.crm.prospecting.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.EngagementStatus;
import com.prosperr.crm.shared.domain.TaskType;
import com.prosperr.crm.shared.domain.WorkingDays;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** PRD reference list "Call outcomes and their rules" and decision #7. */
class OutcomePlannerTest {
    final OutcomePlanner planner = new OutcomePlanner(WorkingDays.weekdaysOnly());
    final LocalDate fri = LocalDate.of(2026, 11, 20);

    final OutcomeRule didNotPick = new OutcomeRule("DID_NOT_PICK", "Did Not Pick", EngagementStatus.CONTACTED,
            TaskType.CALL, 2, false, false, 6, EngagementStatus.UNRESPONSIVE, 30, true, false, false, false, false);
    final OutcomeRule callLater = new OutcomeRule("CALL_LATER", "Call Later", EngagementStatus.FOLLOW_UP_PENDING,
            TaskType.CALL, null, true, false, null, null, null, false, false, false, false, false);
    final OutcomeRule notInterested = new OutcomeRule("NOT_INTERESTED", "Not Interested", EngagementStatus.NOT_RELEVANT,
            null, null, false, true, null, null, 90, false, false, false, false, false);
    final OutcomeRule wrongNumber = new OutcomeRule("WRONG_NUMBER", "Wrong Number", null, TaskType.FIND_NUMBER, 1,
            false, false, null, null, null, false, true, false, false, false);
    final OutcomeRule wrongIcp = new OutcomeRule("WRONG_ICP", "Wrong ICP", EngagementStatus.NOT_RELEVANT, null, null,
            false, false, null, null, null, false, false, true, false, false);
    final OutcomeRule booked = new OutcomeRule("L1_BOOKED", "Demo / L1 Booked", EngagementStatus.MEETING_SCHEDULED,
            null, null, false, false, null, null, null, false, false, false, true, false);
    final OutcomeRule referred = new OutcomeRule("REFERRED", "Referred", EngagementStatus.REFERRED, TaskType.CALL, 1,
            false, false, null, null, null, false, false, false, false, true);

    @Test
    void M2_3_didNotPick_contacted_callIn2WorkingDays_offerSequence() {
        OutcomePlan p = planner.plan(didNotPick, 0, fri, null, null);
        assertEquals(EngagementStatus.CONTACTED, p.newStatus());
        assertEquals(TaskType.CALL, p.taskType());
        assertEquals(LocalDate.of(2026, 11, 24), p.taskDue(), "Fri + 2 working days = Tue");
        assertTrue(p.offerSequence(), "M3-7");
        assertEquals(1, p.attemptsAfter());
    }

    @Test
    void Decision7_sixthTry_unresponsive_30DayRevisit() {
        OutcomePlan p = planner.plan(didNotPick, 5, fri, null, null);
        assertTrue(p.maxAttemptsReached());
        assertEquals(EngagementStatus.UNRESPONSIVE, p.newStatus());
        assertEquals(fri.plusDays(30), p.revisitOn());
        assertEquals(fri.plusDays(30), p.taskDue());
        assertFalse(p.offerSequence());
    }

    @Test
    void M2_3_callLater_needsDate() {
        assertThrows(DomainException.class, () -> planner.plan(callLater, 0, fri, null, null));
        OutcomePlan p = planner.plan(callLater, 3, fri, fri.plusDays(10), null);
        assertEquals(EngagementStatus.FOLLOW_UP_PENDING, p.newStatus());
        assertEquals(fri.plusDays(10), p.taskDue());
        assertEquals(0, p.attemptsAfter(), "reaching the person resets attempts");
    }

    @Test
    void M2_3_notInterested_needsReason_90DayRevisit() {
        assertThrows(DomainException.class, () -> planner.plan(notInterested, 0, fri, null, ""));
        OutcomePlan p = planner.plan(notInterested, 0, fri, null, "Already has a vendor");
        assertEquals(EngagementStatus.NOT_RELEVANT, p.newStatus());
        assertEquals(fri.plusDays(90), p.revisitOn());
    }

    @Test
    void M1_3_wrongNumber_marksInvalidAndCreatesFindNumberTask() {
        OutcomePlan p = planner.plan(wrongNumber, 2, fri, null, null);
        assertNull(p.newStatus(), "no change to engagement");
        assertTrue(p.markPhoneInvalid());
        assertEquals(TaskType.FIND_NUMBER, p.taskType());
    }

    @Test
    void Reference_wrongIcpSetsAccountIcpFitNo() {
        assertTrue(planner.plan(wrongIcp, 0, fri, null, null).setIcpFitNo());
    }

    @Test
    void Reference_l1BookedOpensBookingAndSqlChecklist() {
        OutcomePlan p = planner.plan(booked, 0, fri, null, null);
        assertTrue(p.openL1Booking());
        assertEquals(EngagementStatus.MEETING_SCHEDULED, p.newStatus());
    }

    @Test
    void M2_5_referralCreatesReferredStakeholder() {
        OutcomePlan p = planner.plan(referred, 0, fri, null, null);
        assertTrue(p.createReferral());
        assertEquals(EngagementStatus.REFERRED, p.newStatus());
    }

    @Test
    void M2_3_planIsEditableBeforeSave() {
        OutcomePlan p = planner.plan(didNotPick, 0, fri, null, null)
                .withTask(TaskType.CALL, fri.plusDays(7)).withStatus(EngagementStatus.FOLLOW_UP_PENDING);
        assertEquals(fri.plusDays(7), p.taskDue());
        assertEquals(EngagementStatus.FOLLOW_UP_PENDING, p.newStatus());
    }
}
