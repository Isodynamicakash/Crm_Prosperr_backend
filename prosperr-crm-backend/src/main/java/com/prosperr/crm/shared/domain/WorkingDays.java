package com.prosperr.crm.shared.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Adds working days (Mon–Fri, minus optional holidays). Used for call retry cadence. */
public final class WorkingDays {
    private final Set<LocalDate> holidays;
    public WorkingDays(Set<LocalDate> holidays) { this.holidays = Set.copyOf(holidays); }
    public static WorkingDays weekdaysOnly() { return new WorkingDays(Set.of()); }

    public boolean isWorkingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public LocalDate add(LocalDate start, int days) {
        if (days < 0) throw new IllegalArgumentException("days must be >= 0");
        LocalDate d = start;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (isWorkingDay(d)) added++;
        }
        return d;
    }
}
