package com.longvin.trading.config;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.accounts.Broker;
import com.longvin.trading.repository.AccountRepository;
import com.longvin.trading.repository.BrokerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeds minimal reference data for the simulator profile so that incoming FIX messages can be persisted.
 *
 * Why: Order.account is non-nullable and Account.broker is non-nullable. During simulator testing we may
 * receive ExecutionReports with Account=<PRIMARY_ACCOUNT> and shadow Account values before any data exists.
 */
@Component
@Profile("simulator")
public class SimulatorDataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorDataBootstrap.class);

    private static final String SIM_BROKER_CODE = "SIM";
    private static final String SIM_BROKER_NAME = "SIMULATOR";

    private final FixClientProperties fixClientProperties;
    private final BrokerRepository brokerRepository;
    private final AccountRepository accountRepository;

    public SimulatorDataBootstrap(FixClientProperties fixClientProperties,
                                  BrokerRepository brokerRepository,
                                  AccountRepository accountRepository) {
        this.fixClientProperties = fixClientProperties;
        this.brokerRepository = brokerRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Broker broker = ensureSimulatorBroker();

        List<String> accountsToEnsure = new ArrayList<>();
        Optional<String> primary = fixClientProperties.getPrimaryAccount();
        primary.ifPresent(accountsToEnsure::add);
        accountsToEnsure.addAll(fixClientProperties.getShadowAccountValues());

        if (accountsToEnsure.isEmpty()) {
            log.warn("Simulator bootstrap: no accounts configured (primary-account/shadow-accounts empty). Skipping account seeding.");
            return;
        }

        for (String accountNumber : accountsToEnsure) {
            if (accountNumber == null || accountNumber.isBlank()) {
                continue;
            }
            ensureAccount(broker, accountNumber.trim(), primary.orElse(null));
        }
    }

    private Broker ensureSimulatorBroker() {
        return brokerRepository.findByCode(SIM_BROKER_CODE)
                .or(() -> brokerRepository.findByName(SIM_BROKER_NAME))
                .orElseGet(() -> {
                    Broker created = Broker.builder()
                            .name(SIM_BROKER_NAME)
                            .code(SIM_BROKER_CODE)
                            .description("Auto-created broker for simulator profile")
                            .active(true)
                            .build();
                    Broker saved = brokerRepository.save(created);
                    log.info("Simulator bootstrap: created broker id={} name={} code={}", saved.getId(), saved.getName(), saved.getCode());
                    return saved;
                });
    }

    private void ensureAccount(Broker broker, String accountNumber, String primaryAccountNumber) {
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            return;
        }

        AccountType type = (primaryAccountNumber != null && primaryAccountNumber.equalsIgnoreCase(accountNumber))
                ? AccountType.PRIMARY
                : AccountType.SHADOW;

        Account created = Account.builder()
                .accountNumber(accountNumber)
                .description("Auto-created account for simulator profile (" + type + ")")
                .broker(broker)
                .accountType(type)
                .strategyKey(type == AccountType.PRIMARY ? "SIM_PRIMARY" : "SIM_SHADOW")
                .active(true)
                .build();

        Account saved = accountRepository.save(created);
        log.info("Simulator bootstrap: created account id={} number={} type={}", saved.getId(), saved.getAccountNumber(), saved.getAccountType());
    }
}


