package com.prosperr.crm.account.infrastructure;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stakeholder_email")
public class StakeholderEmail {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID stakeholderId;
    private String email;
    @Column(insertable = false, updatable = false)
    private String emailNormalized;
    @Column(name = "is_primary")
    private boolean primary;
    @Column(name = "is_valid")
    private boolean valid = true;
    private Instant bouncedAt;

    // accessors
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getStakeholderId() { return stakeholderId; }
    public void setStakeholderId(UUID stakeholderId) { this.stakeholderId = stakeholderId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getEmailNormalized() { return emailNormalized; }
    public void setEmailNormalized(String emailNormalized) { this.emailNormalized = emailNormalized; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public Instant getBouncedAt() { return bouncedAt; }
    public void setBouncedAt(Instant bouncedAt) { this.bouncedAt = bouncedAt; }
}
