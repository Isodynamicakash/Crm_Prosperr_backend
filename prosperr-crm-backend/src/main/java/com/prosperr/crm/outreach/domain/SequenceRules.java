package com.prosperr.crm.outreach.domain;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.StopReason;

import java.util.Optional;

/** PRD M3-2, M3-6, M3-9: who may be enrolled, when a running sequence stops, and the daily cap. */
public final class SequenceRules {

    public record ThreadState(boolean replied, boolean bounced, boolean optedOut, boolean l1Booked,
                              boolean doNotContact) {}

    public void checkEnroll(boolean hasValidEmail, boolean doNotContact, boolean alreadyRunning) {
        if (!hasValidEmail) throw new DomainException("NO_VALID_EMAIL", "Stakeholder has no valid email");
        if (doNotContact) throw new DomainException("DO_NOT_CONTACT", "Stakeholder is marked do-not-contact");
        if (alreadyRunning) throw new DomainException("ALREADY_RUNNING", "Stakeholder is already in a running sequence");
    }

    /** Checked before every send (M3-6). */
    public Optional<StopReason> stopReason(ThreadState s) {
        if (s.replied()) return Optional.of(StopReason.REPLY);
        if (s.bounced()) return Optional.of(StopReason.BOUNCE);
        if (s.optedOut() || s.doNotContact()) return Optional.of(StopReason.OPT_OUT);
        if (s.l1Booked()) return Optional.of(StopReason.L1_BOOKED);
        return Optional.empty();
    }

    public boolean underDailyCap(int sentToday, int cap) { return sentToday < cap; }
}
