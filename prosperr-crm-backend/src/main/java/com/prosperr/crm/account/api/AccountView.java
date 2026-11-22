package com.prosperr.crm.account.api;

import com.prosperr.crm.shared.domain.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AccountView(UUID id, String name, String domain, String website, UUID industryId, String subIndustry,
                          UUID cityId, Integer employeeCount, Integer taxableEmployeeCount, Integer licensePotential,
                          SizeBucket sizeBucket, UUID leadSourceId, String apolloId, UUID aeOwnerId, UUID sqlCreditBdrId,
                          LifecycleStage stage, Zone zone, Instant pipelineSince, AccountStatus status,
                          UUID inactiveReasonId, String inactiveRemark, Instant inactiveSince, LocalDate revisitOn,
                          IcpFit icpFit, IntentRating intentRating, String intentReason, String nextStep,
                          LocalDate nextStepDue, Instant lastActivityAt, Instant reviewFlaggedAt, Instant createdAt,
                          int version) {}
