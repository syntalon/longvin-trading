package com.longvin.trading.fix;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.executionReportHandler.ExecutionReportProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.*;

/**
 * QuickFIX/J Application for order-entry initiator session (OS111->OPAL).
 * Handles logon suppression, locate responses, locate confirmations, and order placement.
 */
@Component
public class OrderEntryApplication extends MessageCracker implements Application {

    private static final Logger log = LoggerFactory.getLogger(OrderEntryApplication.class);
    
    private final FixClientProperties properties;
    private final FixSessionRegistry sessionRegistry;
    private final InitiatorLogonGuard initiatorLogonGuard;
    private final ExecutionReportProcessor executionReportProcessor;

    public OrderEntryApplication(FixClientProperties properties,
                                 FixSessionRegistry sessionRegistry,
                                 InitiatorLogonGuard initiatorLogonGuard,
                                 ExecutionReportProcessor executionReportProcessor) {
        this.properties = properties;
        this.sessionRegistry = sessionRegistry;
        this.initiatorLogonGuard = initiatorLogonGuard;
        this.executionReportProcessor = executionReportProcessor;
    }

    @Override
    public void onCreate(SessionID sessionID) {
        log.info("Created order-entry initiator session {}", sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        sessionRegistry.register("initiator", sessionID);
        log.info("Logged on to order-entry initiator session {}", sessionID);
    }

    @Override
    @SuppressWarnings("resource")
    public void onLogout(SessionID sessionID) {
        sessionRegistry.unregister("initiator", sessionID);
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                int targetSeqNum = session.getExpectedTargetNum();
                log.warn("Logged out from order-entry initiator session {} - Session state: isLoggedOn={}, isEnabled={}, expectedTargetSeqNum={}", 
                    sessionID, session.isLoggedOn(), session.isEnabled(), targetSeqNum);
            } else {
                log.warn("Logged out from order-entry initiator session {} - Session object not found", sessionID);
            }
        } catch (Exception e) {
            log.warn("Logged out from order-entry initiator session {} - Error getting session details: {}", sessionID, e.getMessage());
        }
        log.warn("Logged out from order-entry initiator session {} - this may indicate a connection issue or server-side timeout", sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("A".equals(msgType)) {
                // Initiator Logon request: optionally suppress logon attempts and always request seq reset
                if (!initiatorLogonGuard.isLogonAllowed(sessionID)) {
                    log.info("Logon attempt suppressed for session {} until {}", sessionID, initiatorLogonGuard.getNextAllowedLogonFormatted());
                    // Throw DoNotSend unchecked - QuickFIX/J will catch it even though toAdmin() doesn't declare it
                    throwDoNotSendUnchecked();
                }
                if (!message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD)) {
                    message.setField(new quickfix.field.ResetSeqNumFlag(true));
                }
                
                // Add username/password if configured
                String username = properties.getLogonUsername();
                if (username != null && !username.isBlank()) {
                    message.setField(new quickfix.field.Username(username));
                }
                String password = properties.getLogonPassword();
                if (password != null && !password.isBlank()) {
                    message.setField(new quickfix.field.Password(password));
                }
            }
        } catch (Exception e) {
            // DoNotSend is intentionally thrown to suppress message sending (e.g., when logon is suppressed)
            // QuickFIX/J framework handles this exception and prevents the message from being sent
            // This is expected behavior, not an error, so we don't log it - re-throw using type erasure
            if (e instanceof DoNotSend) {
                rethrowDoNotSend((DoNotSend) e);
            }
            // Log other exceptions as warnings
            String errorMsg = e.getMessage();
            if (errorMsg == null) {
                log.warn("Error processing toAdmin for order-entry session {}: {} - {}", 
                    sessionID, e.getClass().getSimpleName(), e);
            } else {
                log.warn("Error processing toAdmin for order-entry session {}: {}", sessionID, errorMsg, e);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Throwable> void throwDoNotSendUnchecked() throws T {
        throw (T) new DoNotSend();
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Throwable> void rethrowDoNotSend(DoNotSend e) throws T {
        throw (T) e;
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) throws FieldNotFound, IncorrectTagValue, RejectLogon {
        // Handle "not trade day" logout messages
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("5".equals(msgType)) {
                String text = message.isSetField(quickfix.field.Text.FIELD) ? message.getString(quickfix.field.Text.FIELD) : null;
                if (text != null && text.toLowerCase().contains("not trade day")) {
                    initiatorLogonGuard.markNotTradingDay(sessionID, text);
                    // Logon attempts will be suppressed in toAdmin() until the next trading window
                    initiatorLogonGuard.scheduleResume(() -> {});
                }
            }
        } catch (Exception e) {
            log.debug("Error processing fromAdmin for order-entry session {}: {}", sessionID, e.getMessage());
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("0".equals(msgType)) {
                // Log outgoing heartbeat
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.debug("Sending heartbeat to {} (seqNum={})", sessionID, seqNum);
            }
        } catch (Exception e) {
            // Don't block message sending if logging fails
            log.trace("Could not log outgoing message: {}", e.getMessage());
        }
    }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        // Crack the message for business logic processing
        crack(message, sessionID);
    }

    @Override
    public void onMessage(Message message, SessionID sessionID)
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);

        // Log incoming heartbeats at DEBUG level
        if ("0".equals(msgType)) {
            try {
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                String senderCompId = message.getHeader().getString(quickfix.field.SenderCompID.FIELD);
                String targetCompId = message.getHeader().getString(quickfix.field.TargetCompID.FIELD);
                log.debug("Received heartbeat from {} -> {} (seqNum={})", senderCompId, targetCompId, seqNum);
            } catch (Exception e) {
                log.trace("Could not log incoming heartbeat: {}", e.getMessage());
            }
            // Heartbeats are handled automatically by QuickFIX/J
            return;
        }

        // Handle ExecutionReports (MsgType=8) and Short Locate Quote Responses (MsgType=S)
        // Both are processed by the ExecutionReportProcessor chain
        if (quickfix.field.MsgType.EXECUTION_REPORT.equals(msgType) || "S".equals(msgType)) {
            executionReportProcessor.process(message, sessionID);
        } else {
            // Other message types
            crack(message, sessionID);
        }
    }
}
