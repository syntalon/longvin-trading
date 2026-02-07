package com.longvin.trading.fix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guard that determines when the initiator session is allowed to attempt a logon.
 * Enforces:
 * - No connection before trading-start-hour (e.g. 4 AM ET)
 * - No connection after trading-end-hour (e.g. 8 PM ET)
 * - Server "not trading day" override via markNotTradingDay()
 */
@Component
public class InitiatorLogonGuard {

    private static final Logger log = LoggerFactory.getLogger(InitiatorLogonGuard.class);
    private static final ZoneId TRADING_ZONE = ZoneId.of("America/New_York");

    private final int resumeHour;
    private final int tradingStartHour;
    private final int tradingEndHour;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Instant> nextAllowedLogon = new AtomicReference<>(Instant.EPOCH);

    public InitiatorLogonGuard(@Value("${trading.initiator.non-trading-resume-hour:6}") int resumeHour,
                               @Value("${trading.initiator.trading-start-hour:4}") int tradingStartHour,
                               @Value("${trading.initiator.trading-end-hour:20}") int tradingEndHour,
                               @Qualifier("initiatorLogonGuardScheduler") ScheduledExecutorService scheduler) {
        this.resumeHour = resumeHour;
        this.tradingStartHour = tradingStartHour;
        this.tradingEndHour = tradingEndHour;
        this.scheduler = scheduler;
    }

    /** Returns true if connection/logon is allowed (for scheduler; no session needed). */
    public boolean isConnectionAllowed() {
        Instant now = Instant.now();
        if (now.isBefore(nextAllowedLogon.get())) {
            return false;
        }
        int hour = now.atZone(TRADING_ZONE).getHour();
        return hour >= tradingStartHour && hour < tradingEndHour;
    }

    public boolean isLogonAllowed(SessionID sessionID) {
        if (isConnectionAllowed()) {
            return true;
        }
        log.debug("Logon suppressed for session {} until {}", sessionID, getNextAllowedLogonFormatted());
        return false;
    }

    public void markNotTradingDay(SessionID sessionID, String reason) {
        ZonedDateTime nowTradingZone = ZonedDateTime.now(TRADING_ZONE);
        ZonedDateTime nextTradingStart = nowTradingZone.toLocalDate()
                .plusDays(1)
                .atStartOfDay(TRADING_ZONE)
                .plusHours(resumeHour);
        nextAllowedLogon.set(nextTradingStart.toInstant());
        log.warn("Server indicated non-trading day for session {} (reason: {}). Suppressing logon attempts until {} {}",
            sessionID, reason, nextTradingStart, TRADING_ZONE);
    }

    /**
     * Schedule a callback to be executed when the next trading window starts.
     * The callback should resume the initiator session.
     */
    public void scheduleResume(Runnable resumeCallback) {
        ZonedDateTime nextAllowed = getNextAllowedLogon().orElse(null);
        if (nextAllowed == null) {
            log.warn("Cannot schedule resume: next allowed logon time is not set");
            return;
        }
        long delaySeconds = Math.max(0, Duration.between(Instant.now(), nextAllowed.toInstant()).getSeconds());
        log.info("Scheduling initiator resume in {} seconds (at {})", delaySeconds, nextAllowed);
        scheduler.schedule(() -> {
            log.info("Executing scheduled initiator resume (scheduled time: {})", nextAllowed);
            nextAllowedLogon.set(Instant.EPOCH);
            try {
                resumeCallback.run();
                log.info("Initiator resume callback completed successfully");
            } catch (Exception e) {
                log.error("Error executing initiator resume callback", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public String getNextAllowedLogonFormatted() {
        return getNextAllowedLogon()
            .map(ZonedDateTime::toString)
            .orElse("immediately");
    }

    public Optional<ZonedDateTime> getNextAllowedLogon() {
        Instant override = nextAllowedLogon.get();
        if (!override.equals(Instant.EPOCH)) {
            return Optional.of(ZonedDateTime.ofInstant(override, TRADING_ZONE));
        }
        ZonedDateTime nowEt = ZonedDateTime.now(TRADING_ZONE);
        int hour = nowEt.getHour();
        if (hour >= tradingStartHour && hour < tradingEndHour) {
            return Optional.empty(); // currently allowed
        }
        ZonedDateTime next;
        if (hour < tradingStartHour) {
            next = nowEt.toLocalDate().atStartOfDay(TRADING_ZONE).plusHours(tradingStartHour);
        } else {
            next = nowEt.toLocalDate().plusDays(1).atStartOfDay(TRADING_ZONE).plusHours(tradingStartHour);
        }
        return Optional.of(next);
    }
}
