package com.prosperr.crm.reporting.api;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** A BDR's performance over a period as plain numbers, for the voice coach. Read-only. */
public interface PerformanceApi {
    /** preset / from / to work like the dashboards (7, 15, 30, month, quarter, or a from-to range). */
    Map<String, Object> performance(UUID bdrId, String preset, LocalDate from, LocalDate to);
}
