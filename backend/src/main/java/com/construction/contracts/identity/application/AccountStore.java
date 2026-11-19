package com.construction.contracts.identity.application;

import com.construction.contracts.identity.domain.Account;
import java.util.Optional;

public interface AccountStore {
    Optional<Account> findByEmail(String email);

    Optional<Account> findById(Long id);

    <S extends Account> S saveAndFlush(S account);
}
