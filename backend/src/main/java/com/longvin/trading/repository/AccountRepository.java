package com.longvin.trading.repository;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.accounts.Broker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
     * Find all accounts by account type.
     */
    List<Account> findByAccountType(AccountType accountType);
    
    /**
     * Find all active accounts by account type.
     */
    List<Account> findByAccountTypeAndActiveTrue(AccountType accountType);
    
    /**
     * Find all active accounts.
     */
    List<Account> findByActiveTrue();
    
    /**
     * Find all accounts for a specific broker.
     */
    List<Account> findByBrokerOrderByAccountNumberAsc(Broker broker);
    
    /**
     * Find all accounts for a specific broker ID.
     */
    List<Account> findByBrokerIdOrderByAccountNumberAsc(Long brokerId);
    
    /**
     * Find active accounts for a specific broker.
     */
    List<Account> findByBrokerAndActiveTrueOrderByAccountNumberAsc(Broker broker);
    
    /**
     * Find active accounts for a specific broker ID.
     */
    List<Account> findByBrokerIdAndActiveTrueOrderByAccountNumberAsc(Long brokerId);
    
    /**
     * Find accounts by account type and broker.
     */
    List<Account> findByAccountTypeAndBroker(AccountType accountType, Broker broker);
    
    /**
     * Find active accounts by account type and broker.
     */
    List<Account> findByAccountTypeAndBrokerAndActiveTrue(AccountType accountType, Broker broker);
    
    /**
     * Check if account exists by account number.
     */
    boolean existsByAccountNumber(String accountNumber);
    
    /**
     * Find primary account (there should typically be only one).
     */
    @Query("SELECT a FROM Account a WHERE a.accountType = 'PRIMARY' AND a.active = true")
    Optional<Account> findPrimaryAccount();
    
    /**
     * Find all shadow accounts.
     */
    @Query("SELECT a FROM Account a WHERE a.accountType = 'SHADOW' AND a.active = true ORDER BY a.accountNumber ASC")
    List<Account> findActiveShadowAccounts();
}

