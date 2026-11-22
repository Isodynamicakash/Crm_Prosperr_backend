package com.prosperr.crm.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StakeholderRepository extends JpaRepository<Stakeholder, UUID> {
    List<Stakeholder> findByAccountId(UUID accountId);
    Optional<Stakeholder> findByLinkedinUrl(String linkedinUrl);
}
