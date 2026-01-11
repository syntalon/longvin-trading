package com.longvin.trading.service;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory cache service for accounts.
 * Loads all accounts into memory at startup and provides fast lookups without database queries.
 * 
 * This service should be used instead of AccountRepository for account lookups to improve performance.
 */
@Service
public class AccountCacheService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountCacheService.class);

    // Cache: account number -> Account
    private final Map<String, Account> accountsByNumber = new ConcurrentHashMap<>();
    
    // Cache: account ID -> Account
    private final Map<Long, Account> accountsById = new ConcurrentHashMap<>();
    
    // Cache: account type -> List of accounts
    private final Map<AccountType, List<Account>> accountsByType = new ConcurrentHashMap<>();
    
    // Cache: active shadow accounts (cached separately for performance)
    private volatile List<Account> activeShadowAccounts = new ArrayList<>();

    private final AccountRepository accountRepository;
    private volatile boolean initialized = false;

    public AccountCacheService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Load accounts into memory cache.
     * This method is called after ApplicationRunner components (like AccountDataInitializer) have run.
     */
    @Override
    @Order(100) // Run after AccountDataInitializer (which has default order 0)
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        reloadCache();
    }

    /**
     * Reload the cache from database.
     * Can be called manually if accounts are modified and cache needs refresh.
     */
    @Transactional(readOnly = true)
    public synchronized void reloadCache() {
        log.info("Loading accounts into memory cache...");
        
        try {
            // Clear existing cache
            accountsByNumber.clear();
            accountsById.clear();
            accountsByType.clear();
            activeShadowAccounts = new ArrayList<>();

            // Load all accounts from database
            // Note: We load accounts with broker to avoid lazy loading issues
            // For lazy-loaded relationships like dasLoginIds, they will be loaded on-demand if accessed
            List<Account> allAccounts = accountRepository.findAll();
            
            // Initialize type map
            for (AccountType type : AccountType.values()) {
                accountsByType.put(type, new ArrayList<>());
            }

            // Populate caches
            for (Account account : allAccounts) {
                accountsByNumber.put(account.getAccountNumber(), account);
                accountsById.put(account.getId(), account);
                
                // Add to type map
                List<Account> typeList = accountsByType.computeIfAbsent(account.getAccountType(), k -> new ArrayList<>());
                typeList.add(account);
                
                // Cache active shadow accounts separately
                if (account.getAccountType() == AccountType.SHADOW && Boolean.TRUE.equals(account.getActive())) {
                    activeShadowAccounts.add(account);
                }
            }

            // Make lists immutable to prevent external modification
            activeShadowAccounts = Collections.unmodifiableList(activeShadowAccounts);
            for (AccountType type : AccountType.values()) {
                List<Account> typeList = accountsByType.get(type);
                if (typeList != null) {
                    accountsByType.put(type, Collections.unmodifiableList(typeList));
                }
            }

            initialized = true;
            log.info("Loaded {} accounts into memory cache ({} active shadow accounts)", 
                    allAccounts.size(), activeShadowAccounts.size());
        } catch (Exception e) {
            log.error("Failed to load accounts into cache", e);
            throw new RuntimeException("Failed to initialize account cache", e);
        }
    }

    /**
     * Find account by account number.
     * @param accountNumber the account number
     * @return Optional containing the account if found
     */
    public Optional<Account> findByAccountNumber(String accountNumber) {
        ensureInitialized();
        Account account = accountsByNumber.get(accountNumber);
        return Optional.ofNullable(account);
    }

    /**
     * Find account by ID.
     * @param id the account ID
     * @return Optional containing the account if found
     */
    public Optional<Account> findById(Long id) {
        ensureInitialized();
        Account account = accountsById.get(id);
        return Optional.ofNullable(account);
    }

    /**
     * Check if account exists by account number.
     * @param accountNumber the account number
     * @return true if account exists
     */
    public boolean existsByAccountNumber(String accountNumber) {
        ensureInitialized();
        return accountsByNumber.containsKey(accountNumber);
    }

    /**
     * Get all active shadow accounts.
     * @return list of active shadow accounts (immutable)
     */
    public List<Account> findActiveShadowAccounts() {
        ensureInitialized();
        return activeShadowAccounts;
    }

    /**
     * Find all accounts by account type.
     * @param accountType the account type
     * @return list of accounts (immutable)
     */
    public List<Account> findByAccountType(AccountType accountType) {
        ensureInitialized();
        return accountsByType.getOrDefault(accountType, Collections.emptyList());
    }

    /**
     * Find all active accounts by account type.
     * @param accountType the account type
     * @return list of active accounts (immutable)
     */
    public List<Account> findByAccountTypeAndActiveTrue(AccountType accountType) {
        ensureInitialized();
        return accountsByType.getOrDefault(accountType, Collections.emptyList())
                .stream()
                .filter(acc -> Boolean.TRUE.equals(acc.getActive()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Find primary account (there should typically be only one).
     * @return Optional containing the primary account if found
     */
    public Optional<Account> findPrimaryAccount() {
        ensureInitialized();
        return accountsByType.getOrDefault(AccountType.PRIMARY, Collections.emptyList())
                .stream()
                .filter(acc -> Boolean.TRUE.equals(acc.getActive()))
                .findFirst();
    }

    /**
     * Get all accounts.
     * @return list of all accounts (immutable)
     */
    public List<Account> findAll() {
        ensureInitialized();
        return Collections.unmodifiableList(new ArrayList<>(accountsByNumber.values()));
    }

    /**
     * Get all active accounts.
     * @return list of all active accounts (immutable)
     */
    public List<Account> findAllActive() {
        ensureInitialized();
        return accountsByNumber.values().stream()
                .filter(acc -> Boolean.TRUE.equals(acc.getActive()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Get cache statistics.
     * @return map containing cache statistics
     */
    public Map<String, Object> getCacheStats() {
        ensureInitialized();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAccounts", accountsByNumber.size());
        stats.put("activeShadowAccounts", activeShadowAccounts.size());
        stats.put("accountsByType", accountsByType.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> e.getValue().size()
                )));
        return stats;
    }

    /**
     * Ensure cache is initialized.
     */
    private void ensureInitialized() {
        if (!initialized) {
            log.warn("Account cache not initialized, attempting to reload...");
            reloadCache();
        }
    }

    /**
     * Check if cache is initialized.
     * @return true if cache is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
}

