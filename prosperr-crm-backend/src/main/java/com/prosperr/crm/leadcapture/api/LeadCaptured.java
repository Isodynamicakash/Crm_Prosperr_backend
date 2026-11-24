package com.prosperr.crm.leadcapture.api;

import java.util.UUID;

/** A prospect filled a QR form. outcome: CREATED, EXISTING (matched an existing contact) or REVIEW (needs the BDR). */
public record LeadCaptured(UUID ownerId, UUID accountId, String personName, String company, String eventName, String outcome) {}
