package com.longvin.trading.fix;

import com.longvin.trading.config.FixClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import quickfix.Session;
import quickfix.SessionID;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service to manage sequence number resets for drop copy sessions.
 * DAS Trader requires sequence numbers to be reset to 1 at the start of each new day's session.
 */
@Slf4j
@Service
public class DropCopySequenceNumberService {

    private final AtomicReference<LocalDate> lastResetDate = new AtomicReference<>(LocalDate.now());
    private final FixClientProperties properties;

    public DropCopySequenceNumberService(FixClientProperties properties) {
        this.properties = properties;
    }

    /**
     * Check if it's a new day and reset sequence numbers if needed.
     * This should be called on session logon to ensure sequence numbers are reset at the start of each day.
     * 
     * @param sessionID The drop copy session ID
     * @return true if sequence numbers were reset, false otherwise
     */
    public boolean resetSequenceNumbersIfNewDay(SessionID sessionID) {
        LocalDate today = LocalDate.now();
        LocalDate lastReset = lastResetDate.get();
        
        if (!today.equals(lastReset)) {
            // It's a new day - reset sequence numbers
            if (lastResetDate.compareAndSet(lastReset, today)) {
                log.info("New day detected for drop copy session {} - resetting sender sequence number to 1 (last reset: {}, today: {})", 
                    sessionID, lastReset, today);
                return resetSequenceNumbers(sessionID);
            }
        }
        
        return false;
    }

    /**
     * Reset sequence numbers to 1 for the drop copy session.
     * This sets the sender sequence number to 1 as required by DAS Trader.
     * 
     * @param sessionID The drop copy session ID
     * @return true if reset was successful, false otherwise
     */
    public boolean resetSequenceNumbers(SessionID sessionID) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null) {
                log.warn("Cannot reset sequence numbers: drop copy session {} not found", sessionID);
                return false;
            }

            // Reset sender sequence number to 1 (this is what we send to DAS Trader)
            session.setNextSenderMsgSeqNum(1);
            
            // Also reset target sequence number to 1 to accept any incoming sequence number from DAS Trader
            session.setNextTargetMsgSeqNum(1);
            
            log.info("Successfully reset sequence numbers for drop copy session {} - senderSeqNum=1, targetSeqNum=1", sessionID);
            return true;
        } catch (Exception e) {
            log.error("Error resetting sequence numbers for drop copy session {}: {}", sessionID, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if sequence numbers should be reset based on what DAS Trader is sending.
     * For an acceptor session, we should sync to whatever sequence number DAS Trader sends.
     * Only reset to 1 if DAS Trader is explicitly sending 1 (they're starting fresh).
     * 
     * Best practice: Always sync to the initiator's sequence number, don't reset on large mismatches.
     * The daily reset at midnight handles the start-of-day reset requirement.
     * 
     * @param sessionID The drop copy session ID
     * @param incomingSeqNum The sequence number DAS Trader is sending
     * @param expectedSeqNum Our expected sequence number
     * @return true if sequence numbers were reset to 1, false if we should sync instead
     */
    public boolean shouldResetTo1OnLogon(SessionID sessionID, int incomingSeqNum, int expectedSeqNum) {
        // Only reset to 1 if DAS Trader is explicitly sending sequence number 1
        // This indicates they're starting fresh (e.g., after a daily reset)
        if (incomingSeqNum == 1) {
            log.info("DAS Trader is sending sequence number 1 - resetting our sequence numbers to 1 to match (expected was {})", 
                expectedSeqNum);
            return true;
        }
        
        // For any other sequence number, we should SYNC to it, not reset
        // Even if there's a large mismatch, DAS Trader knows what sequence number they're at,
        // and we should accept it. The daily reset at midnight handles the start-of-day requirement.
        if (incomingSeqNum != expectedSeqNum) {
            log.info("DAS Trader is sending sequence number {} (we expected {}). " +
                "Will sync to DAS Trader's sequence number instead of resetting. " +
                "Daily reset at midnight handles the start-of-day requirement.", 
                incomingSeqNum, expectedSeqNum);
        }
        
        return false;
    }

    /**
     * Scheduled task to reset sequence numbers at midnight each day.
     * This ensures sequence numbers are reset even if the session is not logged on at midnight.
     */
    @Scheduled(cron = "0 0 0 * * ?") // Run at midnight every day
    public void scheduledResetAtMidnight() {
        try {
            // Find the drop copy session
            SessionID dropCopySessionID = new SessionID(
                "FIX.4.2",
                properties.getDropCopySessionSenderCompId(),
                properties.getDropCopySessionTargetCompId()
            );
            
            Session session = Session.lookupSession(dropCopySessionID);
            if (session != null) {
                log.info("Scheduled midnight reset: resetting sequence numbers for drop copy session {}", dropCopySessionID);
                resetSequenceNumbers(dropCopySessionID);
                lastResetDate.set(LocalDate.now());
            } else {
                log.debug("Scheduled midnight reset: drop copy session {} not found (may not be initialized yet)", dropCopySessionID);
            }
        } catch (Exception e) {
            log.error("Error in scheduled sequence number reset: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the last date when sequence numbers were reset.
     * 
     * @return The last reset date
     */
    public LocalDate getLastResetDate() {
        return lastResetDate.get();
    }
}

