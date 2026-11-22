package com.prosperr.crm.qualification.api;

import java.util.UUID;

public record SqlValidated(UUID submissionId, UUID accountId, UUID opportunityId, UUID validatedBy, UUID submittedBy) {}
