package com.prosperr.crm.shared.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkingDaysTest {
    @Test
    void Decision7_retryEveryTwoWorkingDaysSkipsWeekendsAndHolidays() {
        LocalDate thu = LocalDate.of(2026, 11, 19);
        assertEquals(LocalDate.of(2026, 11, 23), WorkingDays.weekdaysOnly().add(thu, 2));
        WorkingDays withHoliday = new WorkingDays(Set.of(LocalDate.of(2026, 11, 20)));
        assertEquals(LocalDate.of(2026, 11, 24), withHoliday.add(thu, 2));
    }
}
