package com.prosperr.crm.account.infrastructure;

import com.prosperr.crm.shared.domain.BuyingRole;
import com.prosperr.crm.shared.domain.ConnectionType;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.EngagementStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stakeholder")
@DynamicUpdate
public class Stakeholder {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID accountId;
    private UUID bdrOwnerId;
    private String firstName;
    private String lastName;
    private String designation;
    private UUID designationMapId;
    private Short level;
    @Enumerated(EnumType.STRING)
    private Department department = Department.OTHER;
    @Enumerated(EnumType.STRING)
    private BuyingRole buyingRole;
    @Column(name = "is_right_poc")
    private boolean rightPoc;
    @Column(name = "is_primary_spoc")
    private boolean primarySpoc;
    @Column(name = "is_decision_maker")
    private boolean decisionMaker;
    @Enumerated(EnumType.STRING)
    private EngagementStatus engagementStatus = EngagementStatus.NOT_CONTACTED;
    @Enumerated(EnumType.STRING)
    private ConnectionType connectionType;
    private UUID referredById;
    private String linkedinUrl;
    private boolean doNotContact;
    private String dncReason;
    private short callAttempts;
    @Column(insertable = false, updatable = false)
    private Instant lastInteractionAt;
    private Instant nextFollowUpAt;
    private UUID createdBy;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
    @Version
    private int version;

    // accessors
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getBdrOwnerId() { return bdrOwnerId; }
    public void setBdrOwnerId(UUID bdrOwnerId) { this.bdrOwnerId = bdrOwnerId; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public UUID getDesignationMapId() { return designationMapId; }
    public void setDesignationMapId(UUID designationMapId) { this.designationMapId = designationMapId; }
    public Short getLevel() { return level; }
    public void setLevel(Short level) { this.level = level; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public BuyingRole getBuyingRole() { return buyingRole; }
    public void setBuyingRole(BuyingRole buyingRole) { this.buyingRole = buyingRole; }
    public boolean isRightPoc() { return rightPoc; }
    public void setRightPoc(boolean rightPoc) { this.rightPoc = rightPoc; }
    public boolean isPrimarySpoc() { return primarySpoc; }
    public void setPrimarySpoc(boolean primarySpoc) { this.primarySpoc = primarySpoc; }
    public boolean isDecisionMaker() { return decisionMaker; }
    public void setDecisionMaker(boolean decisionMaker) { this.decisionMaker = decisionMaker; }
    public EngagementStatus getEngagementStatus() { return engagementStatus; }
    public void setEngagementStatus(EngagementStatus engagementStatus) { this.engagementStatus = engagementStatus; }
    public ConnectionType getConnectionType() { return connectionType; }
    public void setConnectionType(ConnectionType connectionType) { this.connectionType = connectionType; }
    public UUID getReferredById() { return referredById; }
    public void setReferredById(UUID referredById) { this.referredById = referredById; }
    public String getLinkedinUrl() { return linkedinUrl; }
    public void setLinkedinUrl(String linkedinUrl) { this.linkedinUrl = linkedinUrl; }
    public boolean isDoNotContact() { return doNotContact; }
    public void setDoNotContact(boolean doNotContact) { this.doNotContact = doNotContact; }
    public String getDncReason() { return dncReason; }
    public void setDncReason(String dncReason) { this.dncReason = dncReason; }
    public short getCallAttempts() { return callAttempts; }
    public void setCallAttempts(short callAttempts) { this.callAttempts = callAttempts; }
    public Instant getLastInteractionAt() { return lastInteractionAt; }
    public void setLastInteractionAt(Instant lastInteractionAt) { this.lastInteractionAt = lastInteractionAt; }
    public Instant getNextFollowUpAt() { return nextFollowUpAt; }
    public void setNextFollowUpAt(Instant nextFollowUpAt) { this.nextFollowUpAt = nextFollowUpAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
