package com.prosperr.crm.prospecting.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** PRD M2-1: overdue and due-today tasks, then untouched stakeholders (by priority), then revival accounts due. */
public final class MyDayOrdering {
    public static final Comparator<MyDayItem> ORDER = Comparator
            .comparing(MyDayItem::section)
            .thenComparing(MyDayItem::dueAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparingInt(MyDayItem::priorityRank);

    private MyDayOrdering() {}

    public static List<MyDayItem> sort(List<MyDayItem> items) { return items.stream().sorted(ORDER).toList(); }
}
