package com.longvin.trading.service;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.repository.CopyRuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing and applying copy rules.
 * 
 * Copy rules define how orders from primary accounts are copied to shadow accounts,
 * including copy ratios, triggers, and filters.
 */
@Service
public class CopyRuleService {

    private static final Logger log = LoggerFactory.getLogger(CopyRuleService.class);

    private final CopyRuleRepository copyRuleRepository;
    private final AccountCacheService accountCacheService;
    
    // Cache: primary account ID -> List of active copy rules
    private final Map<Long, List<CopyRule>> rulesByPrimaryAccount = new ConcurrentHashMap<>();
    
    // Cache: primary account ID + shadow account ID -> CopyRule
    private final Map<String, CopyRule> rulesByAccountPair = new ConcurrentHashMap<>();
    
    // Cache: rule -> shadow account ID (to avoid lazy loading issues)
    private final Map<CopyRule, Long> shadowAccountIdByRule = new ConcurrentHashMap<>();
    
    private volatile boolean initialized = false;

    public CopyRuleService(CopyRuleRepository copyRuleRepository, AccountCacheService accountCacheService) {
        this.copyRuleRepository = copyRuleRepository;
        this.accountCacheService = accountCacheService;
    }

    /**
     * Load copy rules into memory cache.
     * Called automatically on startup via @PostConstruct and after rule changes.
     */
    @PostConstruct
    @Transactional(readOnly = true)
    public synchronized void loadRules() {
        log.info("Loading copy rules into memory cache...");
        
        rulesByPrimaryAccount.clear();
        rulesByAccountPair.clear();
        shadowAccountIdByRule.clear();
        
        List<CopyRule> allRules = copyRuleRepository.findByActiveTrue();
        
        for (CopyRule rule : allRules) {
            // Eagerly fetch account IDs while we're in a transaction
            // The JOIN FETCH in the query should have loaded the accounts
            Long primaryAccountId = rule.getPrimaryAccount().getId();
            Long shadowAccountId = rule.getShadowAccount().getId();
            
            // Cache by primary account
            rulesByPrimaryAccount.computeIfAbsent(primaryAccountId, k -> new ArrayList<>())
                    .add(rule);
            
            // Cache by account pair
            String pairKey = primaryAccountId + ":" + shadowAccountId;
            rulesByAccountPair.put(pairKey, rule);
            
            // Cache shadow account ID for this rule (to avoid lazy loading later)
            shadowAccountIdByRule.put(rule, shadowAccountId);
        }
        
        // Make lists immutable
        for (Map.Entry<Long, List<CopyRule>> entry : rulesByPrimaryAccount.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        
        initialized = true;
        log.info("Loaded {} copy rules into memory cache for {} primary accounts", 
                allRules.size(), rulesByPrimaryAccount.size());
    }

    /**
     * Get copy rules for a primary account.
     * Returns rules that match the given order characteristics.
     * 
     * @param primaryAccount The primary account
     * @param ordType Order type (tag 40) - can be null
     * @param symbol Symbol - can be null
     * @param quantity Order quantity - can be null
     * @return List of matching copy rules
     */
    public List<CopyRule> getMatchingRules(Account primaryAccount, Character ordType, 
                                          String symbol, BigDecimal quantity) {
        ensureInitialized();
        
        if (primaryAccount == null) {
            return Collections.emptyList();
        }
        
        List<CopyRule> allRules = rulesByPrimaryAccount.getOrDefault(primaryAccount.getId(), Collections.emptyList());
        
        return allRules.stream()
                .filter(rule -> matchesOrderType(rule, ordType))
                .filter(rule -> matchesQuantity(rule, quantity))
                .sorted(Comparator.comparing(CopyRule::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * Get copy rule for a specific primary->shadow account pair.
     * Returns the rule if it matches the order characteristics.
     */
    public Optional<CopyRule> getRuleForAccountPair(Account primaryAccount, Account shadowAccount,
                                                   Character ordType, 
                                                   String symbol, BigDecimal quantity) {
        ensureInitialized();
        
        if (primaryAccount == null || shadowAccount == null) {
            return Optional.empty();
        }
        
        String pairKey = primaryAccount.getId() + ":" + shadowAccount.getId();
        CopyRule rule = rulesByAccountPair.get(pairKey);
        
        if (rule == null || !rule.getActive()) {
            return Optional.empty();
        }
        
        // Check if rule matches order characteristics
        if (!matchesOrderType(rule, ordType) || 
            !matchesQuantity(rule, quantity)) {
            return Optional.empty();
        }
        
        return Optional.of(rule);
    }

    /**
     * Get the copy route to use when copying regular orders to a shadow account.
     * Returns copyRoute from rule if set, otherwise returns the original route.
     * 
     * @param rule The copy rule
     * @param originalRoute The route from the original message
     * @return The route to use for the copy order
     */
    public String getCopyRoute(CopyRule rule, String originalRoute) {
        if (rule == null || rule.getCopyRoute() == null || rule.getCopyRoute().isBlank()) {
            return originalRoute; // Use original route if rule or copyRoute is not set
        }
        return rule.getCopyRoute();
    }

    /**
     * Get the locate route to use when copying locate orders to a shadow account.
     * Returns locateRoute from rule if set, otherwise falls back to copyRoute, then original route.
     * 
     * @param rule The copy rule
     * @param originalRoute The route from the original message
     * @return The route to use for the locate copy order
     */
    public String getLocateRoute(CopyRule rule, String originalRoute) {
        if (rule == null) {
            return originalRoute; // Use original route if no rule
        }
        
        // First try locateRoute
        if (rule.getLocateRoute() != null && !rule.getLocateRoute().isBlank()) {
            return rule.getLocateRoute();
        }
        
        // Fall back to copyRoute
        if (rule.getCopyRoute() != null && !rule.getCopyRoute().isBlank()) {
            return rule.getCopyRoute();
        }
        
        // Finally, use original route
        return originalRoute;
    }

    /**
     * Get the target route to use when copying to a shadow account.
     * For locate orders, uses locateRoute if set, otherwise falls back to copyRoute or original route.
     * For regular orders, uses copyRoute if set, otherwise uses original route.
     * 
     * @param rule The copy rule
     * @param originalRoute The route from the original message
     * @param isLocateOrder Whether this is a locate order
     * @return The route to use for the copy order
     */
    public String getTargetRoute(CopyRule rule, String originalRoute, boolean isLocateOrder) {
        if (isLocateOrder) {
            return getLocateRoute(rule, originalRoute);
        } else {
            return getCopyRoute(rule, originalRoute);
        }
    }

    /**
     * Get the target route to use when copying to a shadow account (backward compatibility).
     * Assumes it's not a locate order.
     * 
     * @param rule The copy rule
     * @param originalRoute The route from the original message
     * @return The route to use for the copy order
     */
    public String getTargetRoute(CopyRule rule, String originalRoute) {
        return getTargetRoute(rule, originalRoute, false);
    }

    /**
     * Get all shadow accounts that should receive copies for a primary account order.
     * Returns accounts with matching copy rules.
     * Uses AccountCacheService to avoid lazy loading issues.
     */
    public List<Account> getShadowAccountsForCopy(Account primaryAccount, Character ordType,
                                                 String symbol, BigDecimal quantity) {
        ensureInitialized();
        
        if (primaryAccount == null) {
            return Collections.emptyList();
        }
        
        List<CopyRule> matchingRules = getMatchingRules(primaryAccount, ordType, symbol, quantity);
        
        // Extract shadow account IDs from cached map (avoids lazy loading)
        Set<Long> shadowAccountIds = matchingRules.stream()
                .map(rule -> shadowAccountIdByRule.get(rule))
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        
        // Look up accounts from AccountCacheService (avoids lazy loading issues)
        return shadowAccountIds.stream()
                .map(accountCacheService::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(Account::getActive)
                .collect(Collectors.toList());
    }

    /**
     * Calculate the quantity to copy for a given rule and primary order quantity.
     */
    public BigDecimal calculateCopyQuantity(CopyRule rule, BigDecimal primaryQuantity) {
        if (rule == null || primaryQuantity == null) {
            return primaryQuantity; // Default to 1:1 if no rule
        }
        return rule.calculateCopyQuantity(primaryQuantity);
    }

    /**
     * Check if a rule matches the order type.
     */
    private boolean matchesOrderType(CopyRule rule, Character ordType) {
        if (ordType == null) {
            return true; // If order type not specified, match all
        }
        return rule.shouldCopyOrderType(ordType);
    }

    /**
     * Check if a rule matches the quantity (min/max thresholds).
     */
    private boolean matchesQuantity(CopyRule rule, BigDecimal quantity) {
        if (quantity == null) {
            return true; // If quantity not specified, match all
        }
        
        if (rule.getMinQuantity() != null && quantity.compareTo(rule.getMinQuantity()) < 0) {
            return false;
        }
        
        if (rule.getMaxQuantity() != null && quantity.compareTo(rule.getMaxQuantity()) > 0) {
            return false;
        }
        
        return true;
    }

    /**
     * Ensure cache is initialized.
     */
    private void ensureInitialized() {
        if (!initialized) {
            log.warn("Copy rules cache not initialized, loading rules...");
            loadRules();
        }
    }

    /**
     * Refresh the cache (call after rule changes).
     */
    public synchronized void refreshCache() {
        loadRules();
    }
}

