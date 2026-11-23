package com.prosperr.crm.pipeline.api;

import java.util.List;
import java.util.UUID;

/** Open meetings of an account were cancelled (account moved to Inactive or Lost). */
public record MeetingsCancelled(UUID accountId, List<UUID> meetingIds) {}
