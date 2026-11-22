package com.prosperr.crm.prospecting.api;

import java.time.Instant;
import java.util.UUID;

public record CallLogged(UUID activityId, UUID accountId, UUID stakeholderId, String outcomeCode, UUID loggedBy, Instant at) {}
