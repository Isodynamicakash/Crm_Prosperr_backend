package com.prosperr.crm.account.infrastructure;

import com.prosperr.crm.shared.domain.*;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Columns maintained by database triggers (size_bucket_code, lifecycle_stage, last_activity_at, name_normalized,
 * updated_at) are read-only here. @DynamicUpdate writes only changed columns so trigger values are never overwritten.
 */
@Entity
@Table(name = "account")
@DynamicUpdate
public class Account {
    @Id
    private UUID id;
    private String name;
    @Column(insertable = false, updatable = false)
    private String nameNormalized;
    private String domain;
    private String website;
    private UUID industryId;
    private String subIndustry;
    private UUID cityId;
    private Integer employeeCount;
    private Integer taxableEmployeeCount;
    private Integer licensePotential;
    @Column(insertable = false, updatable = false)
    private String sizeBucketCode;
    private UUID leadSourceId;
    private String apolloId;
    private UUID aeOwnerId;
    private UUID sqlCreditBdrId;
    @Enumerated(EnumType.STRING)
    @Column(insertable = false, updatable = false)
    private LifecycleStage lifecycleStage;
    @Enumerated(EnumType.STRING)
    private Zone visibilityZone;
    private Instant pipelineSince;
    @Enumerated(EnumType.STRING)
    private AccountStatus accountStatus;
    private UUID inactiveReasonId;
    private String inactiveRemark;
    private Instant inactiveSince;
    private LocalDate revisitOn;
    @Enumerated(EnumType.STRING)
    private IcpFit icpFit;
    @Enumerated(EnumType.STRING)
    private IntentRating intentRating;
    private String intentReason;
    private String nextStep;
    private LocalDate nextStepDue;
    @Column(insertable = false, updatable = false)
    private Instant lastActivityAt;
    private Instant reviewFlaggedAt;
    private UUID createdBy;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
    @Version
    private int version;

    public SizeBucket sizeBucket() { return sizeBucketCode == null ? null : SizeBucket.valueOf(sizeBucketCode); }

    // accessors
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameNormalized() { return nameNormalized; }
    public void setNameNormalized(String nameNormalized) { this.nameNormalized = nameNormalized; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public UUID getIndustryId() { return industryId; }
    public void setIndustryId(UUID industryId) { this.industryId = industryId; }
    public String getSubIndustry() { return subIndustry; }
    public void setSubIndustry(String subIndustry) { this.subIndustry = subIndustry; }
    public UUID getCityId() { return cityId; }
    public void setCityId(UUID cityId) { this.cityId = cityId; }
    public Integer getEmployeeCount() { return employeeCount; }
    public void setEmployeeCount(Integer employeeCount) { this.employeeCount = employeeCount; }
    public Integer getTaxableEmployeeCount() { return taxableEmployeeCount; }
    public void setTaxableEmployeeCount(Integer taxableEmployeeCount) { this.taxableEmployeeCount = taxableEmployeeCount; }
    public Integer getLicensePotential() { return licensePotential; }
    public void setLicensePotential(Integer licensePotential) { this.licensePotential = licensePotential; }
    public String getSizeBucketCode() { return sizeBucketCode; }
    public void setSizeBucketCode(String sizeBucketCode) { this.sizeBucketCode = sizeBucketCode; }
    public UUID getLeadSourceId() { return leadSourceId; }
    public void setLeadSourceId(UUID leadSourceId) { this.leadSourceId = leadSourceId; }
    public String getApolloId() { return apolloId; }
    public void setApolloId(String apolloId) { this.apolloId = apolloId; }
    public UUID getAeOwnerId() { return aeOwnerId; }
    public void setAeOwnerId(UUID aeOwnerId) { this.aeOwnerId = aeOwnerId; }
    public UUID getSqlCreditBdrId() { return sqlCreditBdrId; }
    public void setSqlCreditBdrId(UUID sqlCreditBdrId) { this.sqlCreditBdrId = sqlCreditBdrId; }
    public LifecycleStage getLifecycleStage() { return lifecycleStage; }
    public void setLifecycleStage(LifecycleStage lifecycleStage) { this.lifecycleStage = lifecycleStage; }
    public Zone getVisibilityZone() { return visibilityZone; }
    public void setVisibilityZone(Zone visibilityZone) { this.visibilityZone = visibilityZone; }
    public Instant getPipelineSince() { return pipelineSince; }
    public void setPipelineSince(Instant pipelineSince) { this.pipelineSince = pipelineSince; }
    public AccountStatus getAccountStatus() { return accountStatus; }
    public void setAccountStatus(AccountStatus accountStatus) { this.accountStatus = accountStatus; }
    public UUID getInactiveReasonId() { return inactiveReasonId; }
    public void setInactiveReasonId(UUID inactiveReasonId) { this.inactiveReasonId = inactiveReasonId; }
    public String getInactiveRemark() { return inactiveRemark; }
    public void setInactiveRemark(String inactiveRemark) { this.inactiveRemark = inactiveRemark; }
    public Instant getInactiveSince() { return inactiveSince; }
    public void setInactiveSince(Instant inactiveSince) { this.inactiveSince = inactiveSince; }
    public LocalDate getRevisitOn() { return revisitOn; }
    public void setRevisitOn(LocalDate revisitOn) { this.revisitOn = revisitOn; }
    public IcpFit getIcpFit() { return icpFit; }
    public void setIcpFit(IcpFit icpFit) { this.icpFit = icpFit; }
    public IntentRating getIntentRating() { return intentRating; }
    public void setIntentRating(IntentRating intentRating) { this.intentRating = intentRating; }
    public String getIntentReason() { return intentReason; }
    public void setIntentReason(String intentReason) { this.intentReason = intentReason; }
    public String getNextStep() { return nextStep; }
    public void setNextStep(String nextStep) { this.nextStep = nextStep; }
    public LocalDate getNextStepDue() { return nextStepDue; }
    public void setNextStepDue(LocalDate nextStepDue) { this.nextStepDue = nextStepDue; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public Instant getReviewFlaggedAt() { return reviewFlaggedAt; }
    public void setReviewFlaggedAt(Instant reviewFlaggedAt) { this.reviewFlaggedAt = reviewFlaggedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
