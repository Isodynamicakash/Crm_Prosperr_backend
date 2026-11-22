package com.prosperr.crm.pipeline.api;

import com.prosperr.crm.shared.domain.MeetingLevel;
import com.prosperr.crm.shared.domain.MeetingOutcome;

import java.util.UUID;

public record MeetingOutcomeRecorded(UUID meetingId, UUID accountId, MeetingLevel level, MeetingOutcome outcome, UUID aeId, UUID bookedBy) {}
