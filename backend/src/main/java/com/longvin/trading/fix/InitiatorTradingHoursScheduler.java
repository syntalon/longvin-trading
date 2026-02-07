package com.longvin.trading.fix;

import com.longvin.trading.config.FixClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pauses the initiator when outside trading hours (4 AM–8 PM ET) to prevent
 * repeated connect→logon→logout cycles when OPAL disconnects sessions after market close.
 * Uses cron at 4 AM and 8 PM ET (2 runs/day) instead of polling.
 */
@Component
public class InitiatorTradingHoursScheduler {

    private static final Logger log = LoggerFactory.getLogger(InitiatorTradingHoursScheduler.class);
    private static final String ZONE_ET = "America/New_York";

    private final FixClientProperties fixProperties;
    private final FixSessionManager fixSessionManager;
    private final InitiatorLogonGuard initiatorLogonGuard;

    private volatile boolean lastKnownAllowed = true;

    public InitiatorTradingHoursScheduler(FixClientProperties fixProperties,
                                          FixSessionManager fixSessionManager,
                                          InitiatorLogonGuard initiatorLogonGuard) {
        this.fixProperties = fixProperties;
        this.fixSessionManager = fixSessionManager;
        this.initiatorLogonGuard = initiatorLogonGuard;
    }

    /** Run once on startup (e.g. app restarts at 10 PM - need to pause). */
    @Scheduled(initialDelayString = "${trading.initiator.trading-hours-check-delay:15000}", fixedDelay = Long.MAX_VALUE)
    public void checkOnStartup() {
        checkTradingHours();
    }

    @Scheduled(cron = "0 0 4 * * ?", zone = ZONE_ET)
    public void atTradingStart() {
        checkTradingHours();
    }

    @Scheduled(cron = "0 0 20 * * ?", zone = ZONE_ET)
    public void atTradingEnd() {
        checkTradingHours();
    }

    private void checkTradingHours() {
        if (!fixProperties.isEnabled()) {
            return;
        }
        boolean allowed = initiatorLogonGuard.isConnectionAllowed();
        if (allowed && !lastKnownAllowed) {
            lastKnownAllowed = true;
            log.info("Within trading hours (4 AM–8 PM ET). Resuming initiator if paused.");
            fixSessionManager.resumeInitiatorIfPaused();
        } else if (!allowed && lastKnownAllowed) {
            lastKnownAllowed = false;
            log.info("Outside trading hours. Pausing initiator until {} to avoid logout loop.",
                    initiatorLogonGuard.getNextAllowedLogonFormatted());
            fixSessionManager.pauseInitiator("Outside trading hours " + initiatorLogonGuard.getNextAllowedLogonFormatted());
        }
    }
}
