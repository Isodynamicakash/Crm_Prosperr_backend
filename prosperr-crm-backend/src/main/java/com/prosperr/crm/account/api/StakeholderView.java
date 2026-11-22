package com.prosperr.crm.account.api;

import com.prosperr.crm.shared.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stakeholder. When {@code masked} is true the viewer is another BDR on a shared prospect account and only
 * sees name, designation and owner (PRD M2-9).
 */
public record StakeholderView(UUID id, UUID accountId, UUID bdrOwnerId, String firstName, String lastName,
                              String designation, UUID designationMapId, Integer level, Department department,
                              int priorityRank, BuyingRole buyingRole, boolean rightPoc, boolean primarySpoc,
                              boolean decisionMaker, EngagementStatus engagement, ConnectionType connectionType,
                              UUID referredById, String linkedinUrl, boolean doNotContact, int callAttempts,
                              Instant lastInteractionAt, Instant nextFollowUpAt, List<EmailView> emails,
                              List<PhoneView> phones, boolean masked) {

    public record EmailView(UUID id, String email, boolean primary, boolean valid) {}
    public record PhoneView(UUID id, String raw, String e164, boolean primary, boolean whatsapp, boolean valid) {
        public String telLink() { return "tel:" + e164; }
        public String whatsappLink() { return "https://wa.me/" + e164.replace("+", ""); }
    }

    public String fullName() { return lastName == null ? firstName : firstName + " " + lastName; }

    public java.util.Optional<EmailView> primaryEmail() {
        return emails.stream().filter(EmailView::valid).sorted((a, b) -> Boolean.compare(b.primary(), a.primary())).findFirst();
    }

    public java.util.Optional<PhoneView> primaryPhone() {
        return phones.stream().filter(PhoneView::valid).sorted((a, b) -> Boolean.compare(b.primary(), a.primary())).findFirst();
    }
}
