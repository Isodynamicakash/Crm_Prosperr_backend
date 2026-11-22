package com.prosperr.crm.qualification.api;

import java.util.UUID;

public record SqlSubmitted(UUID submissionId, UUID accountId, UUID submittedBy) {}
