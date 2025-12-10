package com.longvin.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates pending locate requests for short orders so that the replication flow can
 * await an asynchronous locate approval before mirroring to shadow accounts.
 */
@Service
public class ShortLocateCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ShortLocateCoordinator.class);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final ConcurrentMap<String, PendingLocate> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Register a new locate request for the given order id.
     *
     * @return registration metadata containing the context and whether it was newly registered
     */
    public Registration register(String orderId, String symbol, BigDecimal requestedQty, Duration timeout) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Duration effectiveTimeout = Optional.ofNullable(timeout).orElse(DEFAULT_TIMEOUT);
        PendingLocate context = new PendingLocate(orderId, symbol, requestedQty, effectiveTimeout);
        PendingLocate existing = pendingRequests.putIfAbsent(orderId, context);
        if (existing != null) {
            log.warn("Locate request already pending for orderId={}, reusing existing context", orderId);
            return new Registration(existing, false);
        }
        context.setOnTimeout(() -> pendingRequests.remove(orderId, context));
        return new Registration(context, true);
    }

    public void completeSuccess(String orderId, BigDecimal approvedQty, String locateId, String message) {
        complete(orderId, LocateOutcome.success(approvedQty, locateId, message));
    }

    public void completeFailure(String orderId, String message) {
        complete(orderId, LocateOutcome.failure(message));
    }

    public void completeExceptionally(String orderId, Throwable throwable) {
        PendingLocate context = pendingRequests.remove(orderId);
        if (context != null) {
            context.completeExceptionally(throwable);
        } else {
            log.debug("No pending locate context found for orderId={} when completing exceptionally", orderId);
        }
    }

    private void complete(String orderId, LocateOutcome outcome) {
        PendingLocate context = pendingRequests.remove(orderId);
        if (context == null) {
            log.debug("Locate outcome arrived for orderId={} with no pending context", orderId);
            return;
        }
        context.complete(outcome);
    }

    public record Registration(PendingLocate context, boolean newlyRegistered) {}

    public record LocateOutcome(boolean approved, BigDecimal approvedQty, String locateId, String message) {
        public static LocateOutcome success(BigDecimal qty, String locateId, String message) {
            return new LocateOutcome(true, qty, locateId, message);
        }

        public static LocateOutcome failure(String message) {
            return new LocateOutcome(false, null, null, message);
        }
    }

    public static final class PendingLocate {
        private final String orderId;
        private final String symbol;
        private final BigDecimal requestedQty;
        private final Duration timeout;
        private final Instant registeredAt = Instant.now();
        private final CompletableFuture<LocateOutcome> future = new CompletableFuture<>();
        private final AtomicBoolean timeoutLogged = new AtomicBoolean(false);
        private Runnable onTimeout = () -> {};

        private PendingLocate(String orderId, String symbol, BigDecimal requestedQty, Duration timeout) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.requestedQty = requestedQty;
            this.timeout = timeout;
        }

        public void setOnTimeout(Runnable onTimeout) {
            this.onTimeout = onTimeout == null ? () -> {} : onTimeout;
        }

        public CompletableFuture<LocateOutcome> await() {
            CompletableFuture<LocateOutcome> timed = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return timed.whenComplete((ignored, throwable) -> {
                if (throwable instanceof TimeoutException && timeoutLogged.compareAndSet(false, true)) {
                    log.warn("Locate request timed out for orderId={}, symbol={}, requestedQty={} (waited {} seconds)",
                            orderId, symbol, requestedQty, timeout.toSeconds());
                    onTimeout.run();
                }
            });
        }

        private void complete(LocateOutcome outcome) {
            future.complete(outcome);
        }

        private void completeExceptionally(Throwable throwable) {
            future.completeExceptionally(throwable);
        }
    }
}
