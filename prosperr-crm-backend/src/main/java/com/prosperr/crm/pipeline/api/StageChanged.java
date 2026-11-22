package com.prosperr.crm.pipeline.api;

import com.prosperr.crm.shared.domain.LifecycleStage;

import java.util.UUID;

public record StageChanged(UUID accountId, UUID opportunityId, LifecycleStage from, LifecycleStage to, UUID changedBy) {}
