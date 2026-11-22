package com.prosperr.crm.pipeline.domain;

import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.SizeBucket;

import java.time.Duration;
import java.time.Instant;

/** PRD M6-7: Large and Enterprise accounts stuck at L1 for more than N days need a stagnation reason. */
public final class StagnationPolicy {
    public boolean reasonRequired(SizeBucket bucket, LifecycleStage stage, Instant stageEnteredAt,
                                  Integer thresholdDays, Instant now, boolean reasonAlreadySet) {
        if (reasonAlreadySet || thresholdDays == null || stageEnteredAt == null) return false;
        if (bucket != SizeBucket.LARGE && bucket != SizeBucket.ENTERPRISE) return false;
        if (stage != LifecycleStage.L1) return false;
        return Duration.between(stageEnteredAt, now).toDays() > thresholdDays;
    }
}
