package com.prosperr.crm.outreach.api;

import java.util.UUID;

public final class OutreachEvents {
    private OutreachEvents() {}
    public record EmailReplyReceived(UUID accountId, UUID stakeholderId, UUID ownerId) {}
    public record SequenceSendFailed(UUID enrollmentId, UUID ownerId, String error) {}
}
