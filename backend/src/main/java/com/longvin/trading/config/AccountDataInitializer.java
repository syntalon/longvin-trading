package com.longvin.trading.config;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.accounts.Broker;
import com.longvin.trading.entities.accounts.DasLoginId;
import com.longvin.trading.entities.accounts.Route;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.repository.AccountRepository;
import com.longvin.trading.repository.BrokerRepository;
import com.longvin.trading.repository.CopyRuleRepository;
import com.longvin.trading.repository.DasLoginIdRepository;
import com.longvin.trading.repository.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Initializes default account data on application startup.
 * 
 * This component creates:
 * - OPAL broker
 * - DEFAULT broker (for system routes)
 * - Default routes (LOCATE, TESTSL) for DEFAULT broker
 * - OS111 DAS login ID
 * - Two accounts: TRDAS83 (SHADOW) and TROS107 (PRIMARY), both linked to OPAL broker and OS111 DAS login ID
 * 
 * Can be enabled/disabled via application.yml property:
 *   trading.accounts.initialize-enabled: true|false (default: true)
 */
@Component
@ConditionalOnProperty(
    prefix = "trading.accounts",
    name = "initialize-enabled",
    havingValue = "true",
    matchIfMissing = true  // Default to enabled if property is not set
)
public class AccountDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountDataInitializer.class);

    private static final String BROKER_NAME = "OPAL";
    private static final String DAS_LOGIN_ID = "OS111";
    private static final String[] ACCOUNT_NUMBERS = {"TRDAS83", "TROS107"};

    private final BrokerRepository brokerRepository;
    private final DasLoginIdRepository dasLoginIdRepository;
    private final AccountRepository accountRepository;
    private final RouteRepository routeRepository;
    private final CopyRuleRepository copyRuleRepository;

    public AccountDataInitializer(BrokerRepository brokerRepository,
                                  DasLoginIdRepository dasLoginIdRepository,
                                  AccountRepository accountRepository,
                                  RouteRepository routeRepository,
                                  CopyRuleRepository copyRuleRepository) {
        this.brokerRepository = brokerRepository;
        this.dasLoginIdRepository = dasLoginIdRepository;
        this.accountRepository = accountRepository;
        this.routeRepository = routeRepository;
        this.copyRuleRepository = copyRuleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Starting account data initialization...");
        
        try {
            Broker broker = ensureBroker();
            Broker defaultBroker = ensureDefaultBroker();
            ensureDefaultRoutes(defaultBroker);
            DasLoginId dasLoginId = ensureDasLoginId();

            for (String accountNumber : ACCOUNT_NUMBERS) {
                ensureAccount(broker, dasLoginId, accountNumber);
            }

            // Initialize default copy rules
            ensureDefaultCopyRules();

            log.info("Account data initialization completed successfully");
        } catch (Exception e) {
            log.error("Failed to initialize account data", e);
            throw new RuntimeException("Account data initialization failed", e);
        }
    }

    /**
     * Ensures OPAL broker exists, creating it if necessary.
     */
    private Broker ensureBroker() {
        return brokerRepository.findByName(BROKER_NAME)
                .orElseGet(() -> {
                    Broker broker = Broker.builder()
                            .name(BROKER_NAME)
                            .code("OPAL")
                            .description("OPAL broker")
                            .active(true)
                            .build();
                    Broker saved = brokerRepository.save(broker);
                    log.info("Created broker: id={}, name={}, code={}", saved.getId(), saved.getName(), saved.getCode());
                    return saved;
                });
    }

    /**
     * Ensures DEFAULT broker exists, creating it if necessary.
     * This broker is used for system routes.
     */
    private Broker ensureDefaultBroker() {
        return brokerRepository.findByName("DEFAULT")
                .orElseGet(() -> {
                    Broker broker = Broker.builder()
                            .name("DEFAULT")
                            .code("DEFAULT")
                            .description("Default broker for system routes")
                            .active(true)
                            .build();
                    Broker saved = brokerRepository.save(broker);
                    log.info("Created DEFAULT broker: id={}, name={}", saved.getId(), saved.getName());
                    return saved;
                });
    }

    /**
     * Ensures default routes exist for the DEFAULT broker.
     * Creates LOCATE (Type 1) and TESTSL (Type 0) routes if they don't exist.
     */
    private void ensureDefaultRoutes(Broker defaultBroker) {
        // Ensure LOCATE route (Type 1)
        routeRepository.findByBrokerIdAndNameIgnoreCase(defaultBroker.getId(), "LOCATE")
                .orElseGet(() -> {
                    Route route = Route.builder()
                            .broker(defaultBroker)
                            .name("LOCATE")
                            .routeType(Route.LocateRouteType.TYPE_1)
                            .description("Default Type 1 locate route")
                            .active(true)
                            .priority(0)
                            .build();
                    Route saved = routeRepository.save(route);
                    log.info("Created route: id={}, name={}, routeType={}, broker={}", 
                            saved.getId(), saved.getName(), saved.getRouteType(), defaultBroker.getName());
                    return saved;
                });

        // Ensure TESTSL route (Type 0)
        routeRepository.findByBrokerIdAndNameIgnoreCase(defaultBroker.getId(), "TESTSL")
                .orElseGet(() -> {
                    Route route = Route.builder()
                            .broker(defaultBroker)
                            .name("TESTSL")
                            .routeType(Route.LocateRouteType.TYPE_0)
                            .description("Default Type 0 locate route")
                            .active(true)
                            .priority(1)
                            .build();
                    Route saved = routeRepository.save(route);
                    log.info("Created route: id={}, name={}, routeType={}, broker={}", 
                            saved.getId(), saved.getName(), saved.getRouteType(), defaultBroker.getName());
                    return saved;
                });
    }

    /**
     * Ensures OS111 DAS login ID exists, creating it if necessary.
     */
    private DasLoginId ensureDasLoginId() {
        return dasLoginIdRepository.findByLoginId(DAS_LOGIN_ID)
                .orElseGet(() -> {
                    DasLoginId dasLoginId = DasLoginId.builder()
                            .loginId(DAS_LOGIN_ID)
                            .description("DAS login ID OS111")
                            .active(true)
                            .build();
                    DasLoginId saved = dasLoginIdRepository.save(dasLoginId);
                    log.info("Created DAS login ID: id={}, loginId={}", saved.getId(), saved.getLoginId());
                    return saved;
                });
    }

    /**
     * Ensures an account exists with the given account number, linked to the broker and DAS login ID.
     * TROS107 is set as PRIMARY account with strategy key TEST_PRIMARY.
     * TRDAS83 is set as SHADOW account with strategy key TEST_SHADOW.
     */
    private void ensureAccount(Broker broker, DasLoginId dasLoginId, String accountNumber) {
        // Determine account type: TROS107 is PRIMARY, others are SHADOW
        AccountType accountType = "TROS107".equals(accountNumber) ? AccountType.PRIMARY : AccountType.SHADOW;
        
        // Set strategy key to "TEST" for both TRDAS83 and TROS107 (same group)
        String strategyKey = "TEST";
        
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> {
                    Account newAccount = Account.builder()
                            .accountNumber(accountNumber)
                            .description("Trading account " + accountNumber)
                            .broker(broker)
                            .accountType(accountType)
                            .strategyKey(strategyKey)
                            .active(true)
                            .build();
                    Account saved = accountRepository.save(newAccount);
                    log.info("Created account: id={}, accountNumber={}, broker={}, type={}, strategyKey={}", 
                            saved.getId(), saved.getAccountNumber(), saved.getBroker().getName(), 
                            saved.getAccountType(), saved.getStrategyKey());
                    return saved;
                });

        boolean needsUpdate = false;

        // Update account type if it changed (e.g., if account was created with wrong type before)
        if (account.getAccountType() != accountType) {
            AccountType oldType = account.getAccountType();
            account.setAccountType(accountType);
            needsUpdate = true;
            log.info("Updated account {} type from {} to {}", accountNumber, oldType, accountType);
        }

        // Update strategy key if it changed
        String oldStrategyKey = account.getStrategyKey();
        if (!strategyKey.equals(oldStrategyKey)) {
            account.setStrategyKey(strategyKey);
            needsUpdate = true;
            log.info("Updated account {} strategyKey from {} to {}", accountNumber, oldStrategyKey, strategyKey);
        }

        if (needsUpdate) {
            accountRepository.save(account);
        }

        // Ensure the account is linked to the DAS login ID
        if (!account.getDasLoginIds().contains(dasLoginId)) {
            account.addDasLoginId(dasLoginId);
            accountRepository.save(account);
            log.info("Linked account {} to DAS login ID {}", accountNumber, DAS_LOGIN_ID);
        } else {
            log.debug("Account {} is already linked to DAS login ID {}", accountNumber, DAS_LOGIN_ID);
        }
    }

    /**
     * Ensures default copy rules exist.
     * Creates a copy rule from TROS107 (PRIMARY) to TRDAS83 (SHADOW) if it doesn't exist.
     * Uses the unique constraint on (primary_account_id, shadow_account_id) to check existence.
     */
    private void ensureDefaultCopyRules() {
        // Find primary account (TROS107)
        Account primaryAccount = accountRepository.findByAccountNumber("TROS107")
                .orElseGet(() -> {
                    log.warn("Primary account TROS107 not found, skipping copy rule initialization");
                    return null;
                });

        // Find shadow account (TRDAS83)
        Account shadowAccount = accountRepository.findByAccountNumber("TRDAS83")
                .orElseGet(() -> {
                    log.warn("Shadow account TRDAS83 not found, skipping copy rule initialization");
                    return null;
                });

        if (primaryAccount == null || shadowAccount == null) {
            log.warn("Cannot initialize copy rules: missing primary or shadow account");
            return;
        }

        // Check if copy rule already exists for this account pair
        if (copyRuleRepository.existsByAccountPair(primaryAccount.getId(), shadowAccount.getId())) {
            log.debug("Copy rule already exists for primary account {} -> shadow account {}",
                    primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber());
            return;
        }

        // Create default copy rule: 1:1 multiplier, copy on fill, all order types
        CopyRule defaultRule = CopyRule.builder()
                .primaryAccount(primaryAccount)
                .shadowAccount(shadowAccount)
                .ratioType(CopyRule.CopyRatioType.MULTIPLIER)
                .ratioValue(BigDecimal.ONE) // 1:1 copy
                .orderTypes(null) // Copy all order types
                .copyRoute(null) // Use primary account's route
                .locateRoute(null) // Use primary account's locate route
                .copyBroker(null) // Use shadow account's broker
                .minQuantity(null) // No minimum quantity threshold
                .maxQuantity(null) // No maximum quantity threshold
                .priority(0) // Default priority
                .active(true)
                .description("Default copy rule: TROS107 -> TRDAS83 (1:1 multiplier, all order types)")
                .config(null) // No additional config
                .build();

        CopyRule saved = copyRuleRepository.save(defaultRule);
        log.info("Created default copy rule: id={}, primaryAccount={}, shadowAccount={}, ratioType={}, ratioValue={}",
                saved.getId(), primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber(),
                saved.getRatioType(), saved.getRatioValue());
    }
}

