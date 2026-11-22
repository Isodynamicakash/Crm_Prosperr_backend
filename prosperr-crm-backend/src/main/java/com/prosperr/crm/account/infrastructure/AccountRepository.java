package com.prosperr.crm.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByDomain(String domain);
    Optional<Account> findByNameNormalized(String nameNormalized);
}
