package com.longvin.trading.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "trading.fix")
public class FixClientProperties {

    @Getter
    private final boolean enabled;
    @Getter
    private final String configPath;
    @Getter
    private final String primarySession;
    private final String primaryAccount;
    @Getter
    private final Set<String> shadowSessions;
    @Getter
    private final Map<String, String> shadowAccounts;
    @Getter
    private final Set<String> shadowAccountValues;
    @Getter
    private final String clOrdIdPrefix;

    public FixClientProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("classpath:fix/das-mirror-trading.cfg") String configPath,
            String primarySession,
            String primaryAccount,
            List<String> shadowSessions,
            Map<String, String> shadowAccounts,
            @DefaultValue("MIRROR-") String clOrdIdPrefix) {

        this.enabled = enabled;
        this.configPath = Objects.requireNonNull(configPath, "configPath must not be null");
        this.primarySession = Objects.requireNonNull(primarySession, "primarySession must not be null");
        this.primaryAccount = primaryAccount == null || primaryAccount.isBlank() ? null : primaryAccount;
        this.shadowSessions = Collections.unmodifiableSet(
                shadowSessions == null ? Set.of() : new LinkedHashSet<>(shadowSessions));
        Map<String, String> accounts = shadowAccounts == null ? Map.of() : new LinkedHashMap<>(shadowAccounts);
        this.shadowAccounts = Collections.unmodifiableMap(accounts);
        Set<String> accountValues = new LinkedHashSet<>(accounts.values());
        this.shadowAccountValues = Collections.unmodifiableSet(accountValues);
        this.clOrdIdPrefix = Objects.requireNonNull(clOrdIdPrefix, "clOrdIdPrefix must not be null");
    }

    public Optional<String> getPrimaryAccount() {
        return Optional.ofNullable(primaryAccount);
    }

}
