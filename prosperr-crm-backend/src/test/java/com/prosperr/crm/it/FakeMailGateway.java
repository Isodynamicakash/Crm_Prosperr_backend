package com.prosperr.crm.it;

import com.prosperr.crm.outreach.application.MailGateway;

import java.util.*;

/** Records sent mail; tests mark threads as replied / bounced. */
public class FakeMailGateway implements MailGateway {
    public final List<OutgoingMail> sent = new ArrayList<>();
    public final Set<String> repliedThreads = new HashSet<>();
    public final Set<String> bouncedThreads = new HashSet<>();
    public boolean fail;

    @Override
    public SentMail send(OutgoingMail m) {
        if (fail) throw new IllegalStateException("SMTP down");
        sent.add(m);
        return new SentMail("msg-" + sent.size(), m.threadId() != null ? m.threadId() : "thread-" + sent.size());
    }

    @Override
    public ThreadState check(UUID senderUserId, String threadId) {
        return new ThreadState(repliedThreads.contains(threadId), bouncedThreads.contains(threadId), false);
    }

    public void reset() { sent.clear(); repliedThreads.clear(); bouncedThreads.clear(); fail = false; }
}
