package com.longvin.trading.config;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.accounts.Broker;
import com.longvin.trading.entities.accounts.DasLoginId;
import com.longvin.trading.repository.AccountRepository;
import com.longvin.trading.repository.BrokerRepository;
import com.longvin.trading.repository.DasLoginIdRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes default account data on application startup.
 * 
 * This component creates:
 * - OPAL broker
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

    public AccountDataInitializer(BrokerRepository brokerRepository,
                                  DasLoginIdRepository dasLoginIdRepository,
                                  AccountRepository accountRepository) {
        this.brokerRepository = brokerRepository;
        this.dasLoginIdRepository = dasLoginIdRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Starting account data initialization...");
        
        try {
            Broker broker = ensureBroker();
            DasLoginId dasLoginId = ensureDasLoginId();

            for (String accountNumber : ACCOUNT_NUMBERS) {
                ensureAccount(broker, dasLoginId, accountNumber);
            }

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
}

