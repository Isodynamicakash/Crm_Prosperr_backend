package com.prosperr.crm.outreach.infrastructure;

import com.prosperr.crm.outreach.application.MailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default mail adapter (crm.mail.provider=log): logs instead of sending. Replace with a Gmail adapter in production. */
@Component
@ConditionalOnProperty(name = "crm.mail.provider", havingValue = "log", matchIfMissing = true)
public class LoggingMailGateway implements MailGateway {
    private static final Logger log = LoggerFactory.getLogger(LoggingMailGateway.class);

    @Override
    public SentMail send(OutgoingMail m) {
        log.info("MAIL from={} to={} subject='{}' thread={}", m.fromAddress(), m.to(), m.subject(), m.threadId());
        String id = UUID.randomUUID().toString();
        return new SentMail(id, m.threadId() != null ? m.threadId() : "thread-" + id);
    }

    @Override
    public ThreadState check(UUID senderUserId, String threadId) { return new ThreadState(false, false, false); }
}
