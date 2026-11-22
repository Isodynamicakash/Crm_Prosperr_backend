package com.prosperr.crm.notification.application;

import com.prosperr.crm.account.api.StakeholderEngagementChanged;
import com.prosperr.crm.assignment.api.AccountAssigned;
import com.prosperr.crm.lifecycle.api.LifecycleEvents;
import com.prosperr.crm.outreach.api.OutreachEvents;
import com.prosperr.crm.qualification.api.SqlRejected;
import com.prosperr.crm.qualification.api.SqlSubmitted;
import com.prosperr.crm.qualification.api.SqlValidated;
import com.prosperr.crm.shared.domain.EngagementStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PRD "Notifications" table. Listeners run inside the business transaction, so a notification is stored
 * exactly when the change it describes commits.
 */
@Component
public class NotificationListener {
    private final NotificationService n;
    public NotificationListener(NotificationService n) { this.n = n; }

    @EventListener
    public void sqlValidated(SqlValidated e) {
        if (e.submittedBy() != null) n.notify(List.of(e.submittedBy()), "SQL_APPROVED", "SQL approved: " + n.accountName(e.accountId()),
                "Qualified — the manager will assign an AE.", "account", e.accountId());
    }

    @EventListener
    public void sqlSubmitted(SqlSubmitted e) {
        n.notify(n.managers(), "SQL_SUBMITTED", "New SQL: " + n.accountName(e.accountId()),
                "Review the qualification and assign an AE.", "sql_submission", e.submissionId());
    }

    @EventListener
    public void sqlRejected(SqlRejected e) {
        n.notify(List.of(e.submittedBy()), "SQL_REJECTED", "SQL rejected: " + n.accountName(e.accountId()), e.reason(),
                "account", e.accountId());
    }

    @EventListener
    public void assigned(AccountAssigned e) {
        String name = n.accountName(e.accountId());
        n.notify(List.of(e.newOwnerId()), "ACCOUNT_ASSIGNED", "Assigned to you: " + name, e.ownerType() + " owner", "account", e.accountId());
        if (e.previousOwnerId() != null) {
            n.notify(List.of(e.previousOwnerId()), "ACCOUNT_REASSIGNED", "Reassigned: " + name, "This account moved to another owner",
                    "account", e.accountId());
        }
    }

    @EventListener
    public void unresponsive(StakeholderEngagementChanged e) {
        if (e.to() != EngagementStatus.UNRESPONSIVE) return;
        List<UUID> to = new ArrayList<>();
        if (e.aeOwnerId() != null) to.add(e.aeOwnerId()); else if (e.bdrOwnerId() != null) to.add(e.bdrOwnerId());
        n.notify(to, "SPOC_UNRESPONSIVE", "Unresponsive at " + n.accountName(e.accountId()),
                "Add or contact the next stakeholder (multithreading).", "account", e.accountId());
    }

    @EventListener
    public void flagged(LifecycleEvents.AccountFlaggedForReview e) {
        n.notify(e.aeOwnerId() == null ? n.managers() : List.of(e.aeOwnerId()), "ACCOUNT_TO_BE_MOVED",
                "No activity for a while: " + n.accountName(e.accountId()), "Keep it active with a next step, or move it to Inactive.",
                "account", e.accountId());
    }

    @EventListener
    public void reactivated(LifecycleEvents.AccountReactivated e) {
        List<UUID> to = new ArrayList<>(n.managers());
        if (e.aeOwnerId() != null) to.add(e.aeOwnerId());
        n.notify(to, "ACCOUNT_REACTIVATED", "Reactivated: " + n.accountName(e.accountId()), "Back at " + e.returnedTo(),
                "account", e.accountId());
    }

    @EventListener
    public void inactiveApproval(LifecycleEvents.InactiveApprovalRequested e) {
        n.notify(e.managerId() == null ? n.managers() : List.of(e.managerId()), "INACTIVE_APPROVAL",
                "Approve move to Inactive: " + n.accountName(e.accountId()), null, "account", e.accountId());
    }

    @EventListener
    public void revisit(LifecycleEvents.RevisitDue e) {
        n.notify(e.bdrIds(), "REVISIT_DUE", "Revisit due: " + n.accountName(e.accountId()), "Find a new stakeholder to revive it.",
                "account", e.accountId());
    }

    @EventListener
    public void reply(OutreachEvents.EmailReplyReceived e) {
        n.notify(List.of(e.ownerId()), "EMAIL_REPLY", "Reply from " + n.accountName(e.accountId()), "Call them back.",
                "stakeholder", e.stakeholderId());
    }

    @EventListener
    public void sendFailed(OutreachEvents.SequenceSendFailed e) {
        n.notify(List.of(e.ownerId()), "SEQUENCE_SEND_FAILED", "An email in your sequence failed", e.error(),
                "sequence_enrollment", e.enrollmentId());
    }
}
