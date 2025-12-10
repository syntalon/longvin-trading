package com.longvin.trading.service;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.entities.orders.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes target child account exposure based on a master order and per-account ratio policy.
 */
@Service
public class PositionSyncService {

    private static final Logger log = LoggerFactory.getLogger(PositionSyncService.class);

    private final FixClientProperties properties;
    private final Map<String, ShadowExposure> exposureState = new ConcurrentHashMap<>();

    public PositionSyncService(FixClientProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public ShadowInstruction buildInstruction(String shadowKey,
                                              Order primaryOrder,
                                              BigDecimal masterQty,
                                              BigDecimal availableQty,
                                              Instant locateExpiresAt) {
        FixClientProperties.ShadowAccountPolicy policy = properties.getShadowAccountPolicies().get(shadowKey);
        if (policy == null) {
            log.warn("No policy configured for shadow key {}", shadowKey);
            return ShadowInstruction.skip("missing policy");
        }
        BigDecimal ratio = policy.getRatio();
        BigDecimal targetQty = masterQty.multiply(ratio);
        if (availableQty != null && availableQty.compareTo(targetQty) < 0) {
            targetQty = availableQty;
        }
        BigDecimal currentQty = exposureState
                .computeIfAbsent(shadowKey, key -> new ShadowExposure())
                .getCurrentExposure(primaryOrder.getSymbol());
        BigDecimal delta = targetQty.subtract(currentQty);
        if (delta.signum() == 0) {
            return ShadowInstruction.skip("already synced");
        }
        Direction direction = inferDirection(primaryOrder);
        Duration holdingWindow = policy.getHoldingWindow();
        Duration partialCancelWindow = policy.getPartialCancelWindow();
        boolean allowReplenish = locateExpiresAt != null &&
                Duration.between(Instant.now(), locateExpiresAt).compareTo(policy.getReplenishWindow()) >= 0;
        return new ShadowInstruction(shadowKey, delta, direction, allowReplenish, holdingWindow, partialCancelWindow, locateExpiresAt);
    }

    public void updateExposure(String shadowKey, String symbol, BigDecimal filledQty) {
        exposureState.computeIfAbsent(shadowKey, key -> new ShadowExposure())
                .update(symbol, filledQty);
    }

    private Direction inferDirection(Order primaryOrder) {
        BigDecimal netPosition = Optional.ofNullable(primaryOrder.getCumQty()).orElse(BigDecimal.ZERO);
        if (netPosition.signum() < 0) {
            return Direction.COVER_SHORT;
        }
        return Direction.LONG_OR_INTRADAY;
    }

    public enum Direction {
        COVER_SHORT,
        LONG_OR_INTRADAY
    }

    public record ShadowInstruction(String shadowKey,
                                    BigDecimal deltaQty,
                                    Direction direction,
                                    boolean allowReplenish,
                                    Duration holdingWindow,
                                    Duration partialCancelWindow,
                                    Instant locateExpiresAt) {
        public static ShadowInstruction skip(String reason) {
            return new ShadowInstruction(null, BigDecimal.ZERO, Direction.LONG_OR_INTRADAY, false, Duration.ZERO, Duration.ZERO, null);
        }

        public boolean isSkip() {
            return deltaQty == null || deltaQty.signum() == 0;
        }
    }

    private static final class ShadowExposure {
        private final Map<String, BigDecimal> symbolExposure = new ConcurrentHashMap<>();

        private BigDecimal getCurrentExposure(String symbol) {
            return symbolExposure.getOrDefault(symbol, BigDecimal.ZERO);
        }

        private void update(String symbol, BigDecimal delta) {
            symbolExposure.merge(symbol, delta, BigDecimal::add);
        }
    }
}

