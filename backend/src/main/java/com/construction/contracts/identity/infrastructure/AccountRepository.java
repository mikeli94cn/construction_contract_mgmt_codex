package com.construction.contracts.identity.infrastructure;

import com.construction.contracts.identity.domain.Account;
import com.construction.contracts.identity.application.AccountStore;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long>, AccountStore {
    Optional<Account> findByEmail(String email);
}
