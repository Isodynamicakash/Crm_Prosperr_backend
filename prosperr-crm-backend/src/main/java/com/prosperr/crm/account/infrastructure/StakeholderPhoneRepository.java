package com.prosperr.crm.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StakeholderPhoneRepository extends JpaRepository<StakeholderPhone, UUID> {
    List<StakeholderPhone> findByStakeholderIdIn(Collection<UUID> stakeholderIds);
    List<StakeholderPhone> findByPhoneE164(String e164);
}
