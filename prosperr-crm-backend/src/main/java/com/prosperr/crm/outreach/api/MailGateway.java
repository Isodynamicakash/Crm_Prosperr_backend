package com.prosperr.crm.outreach.api;

import java.util.UUID;

/**
 * Port to the mail provider. Production adapter: Gmail API with each BDR's OAuth token (users.messages.send,
 * threads.get). The default adapter only logs, so the sequence engine can run end to end without Google credentials.
 */
public interface MailGateway {
    record OutgoingMail(UUID senderUserId, String fromAddress, String to, String subject, String body, String threadId) {}
    record SentMail(String messageId, String threadId) {}
    record ThreadState(boolean replied, boolean bounced, boolean optedOut) {}

    SentMail send(OutgoingMail mail);
    ThreadState check(UUID senderUserId, String threadId);
}
