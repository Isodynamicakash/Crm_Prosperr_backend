package com.prosperr.crm.it;

import java.time.*;

/** Test clock: starts on the next weekday at 10:30 IST (inside the email send window) and can be moved. */
public class MutableClock extends Clock {
    private Instant now;

    public MutableClock() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate d = LocalDate.now(ist).plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        now = d.atTime(10, 30).atZone(ist).toInstant();
    }

    public void set(Instant i) { now = i; }
    public void advance(Duration d) { now = now.plus(d); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    @Override public Instant instant() { return now; }
}
