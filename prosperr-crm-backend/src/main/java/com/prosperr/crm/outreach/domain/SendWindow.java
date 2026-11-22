package com.prosperr.crm.outreach.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** PRD M3-9: sequences send only on weekdays between 09:30 and 18:30 IST (admin-editable). */
public final class SendWindow {
    private final Set<DayOfWeek> days;
    private final LocalTime start;
    private final LocalTime end;
    private final ZoneId zone;

    public SendWindow(Set<DayOfWeek> days, LocalTime start, LocalTime end, ZoneId zone) {
        this.days = Set.copyOf(days); this.start = start; this.end = end; this.zone = zone;
    }

    public static SendWindow defaultIst() {
        return new SendWindow(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY), LocalTime.of(9, 30), LocalTime.of(18, 30), ZoneId.of("Asia/Kolkata"));
    }

    public boolean isOpen(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        LocalTime lt = z.toLocalTime();
        return days.contains(z.getDayOfWeek()) && !lt.isBefore(start) && lt.isBefore(end);
    }

    /** The earliest moment at or after t when sending is allowed. */
    public Instant nextOpen(Instant t) {
        if (isOpen(t)) return t;
        ZonedDateTime z = t.atZone(zone);
        LocalDate d = z.toLocalDate();
        if (days.contains(d.getDayOfWeek()) && z.toLocalTime().isBefore(start)) {
            return d.atTime(start).atZone(zone).toInstant();
        }
        do { d = d.plusDays(1); } while (!days.contains(d.getDayOfWeek()));
        return d.atTime(start).atZone(zone).toInstant();
    }

    /** Send time for a step N days after the sequence start, pushed into the window. */
    public Instant stepTime(Instant sequenceStart, int dayOffset) {
        ZonedDateTime base = sequenceStart.atZone(zone).plusDays(dayOffset);
        return nextOpen(base.toInstant());
    }
}
