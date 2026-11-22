package com.prosperr.crm.outreach.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "crm.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SequenceJobs {
    private final SequenceService sequences;
    SequenceJobs(SequenceService sequences) { this.sequences = sequences; }

    @Scheduled(fixedDelayString = "${crm.outreach.send-poll-ms:60000}")
    void send() { sequences.processDue(); }

    @Scheduled(fixedDelayString = "${crm.outreach.reply-poll-ms:900000}")
    void replies() { sequences.pollReplies(); }
}
