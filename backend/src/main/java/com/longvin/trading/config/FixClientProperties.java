package com.longvin.trading.config;

import java.math.BigDecimal;
import java.time.Duration;
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
    @Getter
    private final String dropCopySessionSenderCompId;
    @Getter
    private final String dropCopySessionTargetCompId;
    private final String primaryAccount;
    @Getter
    private final Set<String> shadowSessions;
    @Getter
    private final Map<String, String> shadowAccounts;
    @Getter
    private final Set<String> shadowAccountValues;
    @Getter
    private final String clOrdIdPrefix;
    @Getter
    private final Map<String, ShadowAccountPolicy> shadowAccountPolicies;
    /**
     * Optional initiator logon credentials (sent on MsgType=A as tags 553/554 if configured).
     * Keep empty in source; set via environment variables in production.
     */
    @Getter
    private final String logonUsername;
    @Getter
    private final String logonPassword;

    public FixClientProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("classpath:fix/das-mirror-trading.cfg") String configPath,
            String primarySession,
            @DefaultValue("OS111") String dropCopySessionSenderCompId,
            @DefaultValue("DAST") String dropCopySessionTargetCompId,
            String primaryAccount,
            List<String> shadowSessions,
            Map<String, String> shadowAccounts,
            Map<String, ShadowAccountPolicy> shadowAccountPolicies,
            @DefaultValue("MIRROR-") String clOrdIdPrefix,
            @DefaultValue("") String logonUsername,
            @DefaultValue("") String logonPassword) {

        this.enabled = enabled;
        this.configPath = Objects.requireNonNull(configPath, "configPath must not be null");
        this.primarySession = Objects.requireNonNull(primarySession, "primarySession must not be null");
        this.dropCopySessionSenderCompId = Objects.requireNonNull(dropCopySessionSenderCompId, "dropCopySessionSenderCompId must not be null");
        this.dropCopySessionTargetCompId = Objects.requireNonNull(dropCopySessionTargetCompId, "dropCopySessionTargetCompId must not be null");
        this.primaryAccount = primaryAccount == null || primaryAccount.isBlank() ? null : primaryAccount;
        this.shadowSessions = Collections.unmodifiableSet(
                shadowSessions == null ? Set.of() : new LinkedHashSet<>(shadowSessions));
        Map<String, String> accounts = shadowAccounts == null ? Map.of() : new LinkedHashMap<>(shadowAccounts);
        this.shadowAccounts = Collections.unmodifiableMap(accounts);
        Set<String> accountValues = new LinkedHashSet<>(accounts.values());
        this.shadowAccountValues = Collections.unmodifiableSet(accountValues);
        this.shadowAccountPolicies = Collections.unmodifiableMap(
                shadowAccountPolicies == null ? Map.of() : shadowAccountPolicies);
        this.clOrdIdPrefix = Objects.requireNonNull(clOrdIdPrefix, "clOrdIdPrefix must not be null");
        this.logonUsername = logonUsername == null ? "" : logonUsername;
        this.logonPassword = logonPassword == null ? "" : logonPassword;
    }

    public Optional<String> getPrimaryAccount() {
        return Optional.ofNullable(primaryAccount);
    }

    @Getter
    public static class ShadowAccountPolicy {
        private final BigDecimal ratio;
        private final Duration replenishWindow;
        private final Duration holdingWindow;
        private final Duration partialCancelWindow;

        public ShadowAccountPolicy(
                @DefaultValue("1") BigDecimal ratio,
                @DefaultValue("PT2M") Duration replenishWindow,
                @DefaultValue("PT2M") Duration holdingWindow,
                @DefaultValue("PT30S") Duration partialCancelWindow) {
            this.ratio = ratio == null ? BigDecimal.ONE : ratio;
            this.replenishWindow = replenishWindow == null ? Duration.ofMinutes(2) : replenishWindow;
            this.holdingWindow = holdingWindow == null ? Duration.ofMinutes(2) : holdingWindow;
            this.partialCancelWindow = partialCancelWindow == null ? Duration.ofSeconds(30) : partialCancelWindow;
        }
    }
}
