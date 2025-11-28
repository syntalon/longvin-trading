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
 * This component only tracks state - it does not control the session lifecycle.
 * The actual pause/resume logic should be handled by the component that uses this guard.
 */
@Component
public class InitiatorLogonGuard {

    private static final Logger log = LoggerFactory.getLogger(InitiatorLogonGuard.class);
    private static final ZoneId TRADING_ZONE = ZoneId.of("America/New_York");

    private final int resumeHour;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Instant> nextAllowedLogon = new AtomicReference<>(Instant.EPOCH);

    public InitiatorLogonGuard(@Value("${trading.initiator.non-trading-resume-hour:6}") int resumeHour,
                               @Qualifier("initiatorLogonGuardScheduler") ScheduledExecutorService scheduler) {
        this.resumeHour = resumeHour;
        this.scheduler = scheduler;
    }

    public boolean isLogonAllowed(SessionID sessionID) {
        Instant now = Instant.now();
        Instant allowed = nextAllowedLogon.get();
        boolean permitted = !now.isBefore(allowed);
        if (!permitted) {
            log.debug("Logon suppressed for session {} until {}", sessionID, getNextAllowedLogonFormatted());
        }
        return permitted;
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
        Instant next = nextAllowedLogon.get();
        if (next.equals(Instant.EPOCH)) {
            return Optional.empty();
        }
        return Optional.of(ZonedDateTime.ofInstant(next, TRADING_ZONE));
    }
}
