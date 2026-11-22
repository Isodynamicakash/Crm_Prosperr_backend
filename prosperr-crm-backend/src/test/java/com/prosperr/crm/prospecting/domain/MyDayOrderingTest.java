package com.prosperr.crm.prospecting.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MyDayOrderingTest {
    static MyDayItem item(MyDayItem.Section s, String title, Instant due, int rank) {
        return new MyDayItem(s, null, null, "Acc", null, null, null, null, title, due, rank);
    }

    @Test
    void M2_1_overdueThenTodayThenUntouchedThenRevival() {
        Instant now = Instant.parse("2026-11-20T05:00:00Z");
        var sorted = MyDayOrdering.sort(List.of(
                item(MyDayItem.Section.REVIVAL, "revive", null, 1),
                item(MyDayItem.Section.UNTOUCHED, "payroll", null, 7),
                item(MyDayItem.Section.UNTOUCHED, "cfo", null, 1),
                item(MyDayItem.Section.DUE_TODAY, "today", now, 1),
                item(MyDayItem.Section.OVERDUE, "late", now.minusSeconds(86400), 1)));
        assertEquals(List.of("late", "today", "cfo", "payroll", "revive"),
                sorted.stream().map(MyDayItem::title).toList());
    }
}
