package com.longvin.trading.repository;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Account entities.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    
    /**
     * Find account by account number.
     */
    Optional<Account> findByAccountNumber(String accountNumber);
    
    /**
     * Find all active shadow accounts.
     */
    List<Account> findByAccountTypeAndActiveTrue(AccountType accountType);
    
    /**
     * Find all shadow accounts.
     */
    List<Account> findByAccountType(AccountType accountType);
    
    /**
     * Find all active accounts.
     */
    List<Account> findByActiveTrue();
}

