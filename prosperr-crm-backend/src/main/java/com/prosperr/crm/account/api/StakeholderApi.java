package com.prosperr.crm.account.api;

import com.prosperr.crm.shared.domain.BuyingRole;
import com.prosperr.crm.shared.domain.ConnectionType;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.EngagementStatus;
import com.prosperr.crm.shared.security.Actor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StakeholderApi {

    record CreateStakeholder(UUID accountId, String firstName, String lastName, String fullName, String designation,
                             Department department, BuyingRole buyingRole, List<String> emails, List<String> phones,
                             String linkedinUrl, UUID referredById, ConnectionType connectionType, UUID bdrOwnerId,
                             boolean primarySpoc, boolean decisionMaker, boolean allowSharedPhone) {}

    StakeholderView get(UUID id);
    List<StakeholderView> forAccount(UUID accountId);
    StakeholderView create(CreateStakeholder cmd, Actor actor);
    void setEngagement(UUID stakeholderId, EngagementStatus status, Actor actor);
    void setCallAttempts(UUID stakeholderId, int attempts);
    void setNextFollowUp(UUID stakeholderId, Instant at);
    void markPhoneInvalid(UUID phoneId);
    void markEmailBounced(UUID emailId, Instant at);
    void setDoNotContact(UUID stakeholderId, String reason);
    void setBdrOwner(UUID stakeholderId, UUID bdrId);
}
