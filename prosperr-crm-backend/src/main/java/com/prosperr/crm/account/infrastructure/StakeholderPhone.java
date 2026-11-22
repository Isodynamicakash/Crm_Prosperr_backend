package com.prosperr.crm.account.infrastructure;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stakeholder_phone")
public class StakeholderPhone {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID stakeholderId;
    private String phoneRaw;
    @Column(name = "phone_e164")
    private String phoneE164;
    @Column(name = "is_primary")
    private boolean primary;
    @Column(name = "is_whatsapp")
    private boolean whatsapp;
    @Column(name = "is_valid")
    private boolean valid = true;
    private Instant invalidMarkedAt;

    // accessors
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getStakeholderId() { return stakeholderId; }
    public void setStakeholderId(UUID stakeholderId) { this.stakeholderId = stakeholderId; }
    public String getPhoneRaw() { return phoneRaw; }
    public void setPhoneRaw(String phoneRaw) { this.phoneRaw = phoneRaw; }
    public String getPhoneE164() { return phoneE164; }
    public void setPhoneE164(String phoneE164) { this.phoneE164 = phoneE164; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public boolean isWhatsapp() { return whatsapp; }
    public void setWhatsapp(boolean whatsapp) { this.whatsapp = whatsapp; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public Instant getInvalidMarkedAt() { return invalidMarkedAt; }
    public void setInvalidMarkedAt(Instant invalidMarkedAt) { this.invalidMarkedAt = invalidMarkedAt; }
}