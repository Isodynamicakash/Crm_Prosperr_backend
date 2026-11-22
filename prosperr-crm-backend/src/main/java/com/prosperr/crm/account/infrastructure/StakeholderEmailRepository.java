package com.prosperr.crm.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StakeholderEmailRepository extends JpaRepository<StakeholderEmail, UUID> {
    List<StakeholderEmail> findByStakeholderIdIn(Collection<UUID> stakeholderIds);
    Optional<StakeholderEmail> findByEmailNormalized(String email);
}
