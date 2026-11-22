package com.prosperr.crm.pipeline.api;

import com.prosperr.crm.shared.domain.MeetingLevel;

import java.time.Instant;
import java.util.UUID;

public record MeetingBooked(UUID meetingId, UUID accountId, MeetingLevel level, UUID bookedBy, UUID aeId, Instant scheduledStart) {}
