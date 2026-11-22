package com.prosperr.crm.shared.domain;

import java.time.ZoneId;

/** India Standard Time: all user-facing dates and schedules use it. */
public final class Ist {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private Ist() {}
}
