package com.prosperr.crm.qualification.api;

import java.util.UUID;

public record SqlRejected(UUID submissionId, UUID accountId, UUID submittedBy, String reason) {}
