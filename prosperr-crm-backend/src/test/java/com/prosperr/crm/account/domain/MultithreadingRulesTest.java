package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.EngagementStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MultithreadingRulesTest {

    static StakeholderSnapshot s(String name, Department d, Integer level, int rank, EngagementStatus st, boolean spoc) {
        return new StakeholderSnapshot(UUID.randomUUID(), name, d, level, rank, st, false, spoc, false);
    }

    @Test
    void M2_6_and_M7_1_priorityOrderCfoFirst() {
        var list = List.of(
                s("Payroll Mgr", Department.PAYROLL, 5, 7, EngagementStatus.NOT_CONTACTED, false),
                s("CHRO", Department.HR, 1, 2, EngagementStatus.NOT_CONTACTED, false),
                s("CFO", Department.FINANCE, 1, 1, EngagementStatus.NOT_CONTACTED, false));
        var ordered = new StakeholderPrioritizer().ordered(list);
        assertEquals("CFO", ordered.get(0).name());
        assertEquals("CHRO", ordered.get(1).name());
        assertEquals("Payroll Mgr", ordered.get(2).name());
    }

    @Test
    void M2_6_and_M7_3_suggestNextWhenSpocUnresponsive() {
        var list = List.of(
                s("HR Manager", Department.HR, 5, 8, EngagementStatus.UNRESPONSIVE, true),
                s("CFO", Department.FINANCE, 1, 1, EngagementStatus.UNRESPONSIVE, false),
                s("CHRO", Department.HR, 1, 2, EngagementStatus.NOT_CONTACTED, false));
        assertEquals("CHRO", new StakeholderPrioritizer().suggestNext(list).orElseThrow().name());
    }

    @Test
    void M2_6_doNotContactIsNeverSuggested() {
        var dnc = new StakeholderSnapshot(UUID.randomUUID(), "CFO", Department.FINANCE, 1, 1,
                EngagementStatus.NOT_CONTACTED, true, false, false);
        assertTrue(new StakeholderPrioritizer().suggestNext(List.of(dnc)).isEmpty());
    }

    @Test
    void M7_5_oneUnresponsiveSpocDoesNotMakeAccountInactiveButNeedsMultithreading() {
        var a = new AccountEngagement().assess(List.of(
                s("Junior HR", Department.HR, 5, 8, EngagementStatus.UNRESPONSIVE, true),
                s("CFO", Department.FINANCE, 1, 1, EngagementStatus.CONNECTED, false)));
        assertTrue(a.anyEngaged(), "account still has an engaged stakeholder");
        assertTrue(a.needsMultithreading(), "silent SPOC flags multithreading");
    }

    @Test
    void M7_4_coverageByFunctionAndLevel() {
        var a = new AccountEngagement().assess(List.of(
                s("CFO", Department.FINANCE, 1, 1, EngagementStatus.MEETING_COMPLETED, false),
                s("CHRO", Department.HR, 1, 2, EngagementStatus.CONNECTED, false),
                s("Payroll", Department.PAYROLL, 5, 7, EngagementStatus.CONTACTED, false)));
        assertTrue(a.coverage().get(Department.FINANCE).engaged());
        assertEquals(1, a.coverage().get(Department.FINANCE).bestLevel());
        assertTrue(a.coverage().get(Department.HR).engaged());
        assertFalse(a.coverage().get(Department.PAYROLL).engaged());
        assertFalse(a.needsMultithreading());
    }

    @Test
    void Report2_onlyJuniorStakeholdersEngaged() {
        var a = new AccountEngagement().assess(List.of(
                s("Payroll Exec", Department.PAYROLL, 5, 7, EngagementStatus.CONNECTED, true),
                s("HR Exec", Department.HR, 5, 8, EngagementStatus.CONNECTED, false)));
        assertTrue(a.onlyJuniorEngaged());
        assertTrue(a.needsMultithreading());
    }
}
