package com.longvin.trading.fix;

import com.longvin.trading.executionReportHandler.ExecutionReportProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * QuickFIX/J Application for drop-copy acceptor session (OS111->DAST).
 * Handles incoming messages from DAS Trader, including ExecutionReports, heartbeats, and logon/logout.
 */
@Component
public class DropCopyApplication extends MessageCracker implements Application {

    private static final Logger log = LoggerFactory.getLogger(DropCopyApplication.class);
    
    private final FixSessionRegistry sessionRegistry;
    private final ExecutionReportProcessor executionReportProcessor;
    private final DropCopySequenceNumberService sequenceNumberService;
    
    // Track recent messages from drop copy session (last 100 messages)
    private final BlockingQueue<ReceivedMessage> recentDropCopyMessages = 
        new LinkedBlockingQueue<>(100);
    
    // Diagnostic counters
    private volatile int adminMessageCount = 0;
    private volatile int appMessageCount = 0;
    private volatile long firstAppMessageTime = 0;

    public DropCopyApplication(FixSessionRegistry sessionRegistry,
                               ExecutionReportProcessor executionReportProcessor,
                               DropCopySequenceNumberService sequenceNumberService) {
        this.sessionRegistry = sessionRegistry;
        this.executionReportProcessor = executionReportProcessor;
        this.sequenceNumberService = sequenceNumberService;
    }

    @Override
    public void onCreate(SessionID sessionID) {
        log.info("Created drop-copy acceptor session {}", sessionID);
        
        // Reset sequence numbers to 1 if it's a new day (per DAS Trader requirement)
        sequenceNumberService.resetSequenceNumbersIfNewDay(sessionID);
        
        // Set target sequence number to 1 initially to accept any incoming sequence number >= 1 from DAS Trader
        // The actual value will be synchronized in fromAdmin when Logon is received
        // Sender sequence number is reset to 1 at start of each day per DAS Trader requirement
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                session.setNextTargetMsgSeqNum(1);
                // Ensure sender sequence number is also 1 (may have been reset by sequenceNumberService)
                session.setNextSenderMsgSeqNum(1);
                log.info("Drop copy acceptor session created {} - set targetSeqNum=1 and senderSeqNum=1. " +
                    "Sequence numbers reset to 1 at start of each day per DAS Trader requirement. " +
                    "Will synchronize target sequence number in fromAdmin when Logon is received.", sessionID);
            }
        } catch (Exception e) {
            log.debug("Could not set sequence numbers for drop copy acceptor session {}: {}", sessionID, e.getMessage());
        }
    }

    @Override
    public void onLogon(SessionID sessionID) {
        sessionRegistry.register("acceptor", sessionID);
        log.info("Logged on to drop-copy session {}", sessionID);
        
        // Reset sequence numbers to 1 if it's a new day (per DAS Trader requirement)
        // This ensures sequence numbers are reset at the start of each day's session
        boolean wasReset = sequenceNumberService.resetSequenceNumbersIfNewDay(sessionID);
        if (wasReset) {
            log.info("Drop copy session logged on - sequence numbers reset to 1 for new day");
        }
        
        // Log sequence numbers after logon
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                int expectedSeqNum = session.getExpectedTargetNum();
                log.info("Drop copy session logged on - expected target sequence number: {} (sender sequence number reset to 1 per DAS Trader requirement)", 
                    expectedSeqNum);
            }
        } catch (Exception e) {
            log.warn("Could not check sequence numbers after logon for drop copy session {}: {}", sessionID, e.getMessage());
        }
    }

    @Override
    public void onLogout(SessionID sessionID) {
        sessionRegistry.unregister("acceptor", sessionID);
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                int targetSeqNum = session.getExpectedTargetNum();
                log.warn("Logged out from drop-copy session {} - Session state: isLoggedOn={}, isEnabled={}, expectedTargetSeqNum={}", 
                    sessionID, session.isLoggedOn(), session.isEnabled(), targetSeqNum);
                log.warn("Drop copy session logout - DAS Trader may have rejected our Logon response or closed the connection. " +
                    "Check if DAS Trader sent a Logout message (should appear in logs above). " +
                    "If no Logout message is visible, DAS Trader may have closed the connection without sending one.");
            } else {
                log.warn("Logged out from drop-copy session {} - Session object not found", sessionID);
            }
        } catch (Exception e) {
            log.warn("Logged out from drop-copy session {} - Error getting session details: {}", sessionID, e.getMessage());
        }
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        // No-op for drop-copy acceptor (we only receive admin messages)
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) throws FieldNotFound, IncorrectTagValue, RejectLogon {
        adminMessageCount++;
        
        // Log all admin messages received from drop copy session for debugging
        logDropCopyMessageReceived("ADMIN", message, sessionID);
        
        // Log a warning if we're only receiving admin messages (heartbeats) and no app messages
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("0".equals(msgType)) {
                // Heartbeat - log diagnostic summary every 10 heartbeats
                if (adminMessageCount % 10 == 0) {
                    log.warn("[DROP COPY DIAGNOSTIC] Received {} admin messages (mostly heartbeats), but only {} application messages. " +
                        "If appMessageCount is 0, DAS Trader is NOT sending ExecutionReports or they are being rejected before reaching fromApp(). " +
                        "Check QuickFIX logs for message rejections.", adminMessageCount, appMessageCount);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        
        // Handle sequence number synchronization for drop copy acceptor sessions
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            // Handle SequenceReset messages (MsgType=4) - DAS Trader may send this to reset sequence numbers
            if ("4".equals(msgType)) {
                try {
                    Session session = Session.lookupSession(sessionID);
                    if (session != null) {
                        // Check if this is a sequence reset request
                        if (message.isSetField(quickfix.field.GapFillFlag.FIELD)) {
                            boolean gapFill = message.getBoolean(quickfix.field.GapFillFlag.FIELD);
                            int newSeqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                            
                            if (!gapFill) {
                                // Not a gap fill - this is a sequence reset request
                                log.info("Received SequenceReset message from DAS Trader (newSeqNum={}, gapFill=false). " +
                                    "Resetting sequence numbers to 1 per DAS Trader requirement.", newSeqNum);
                                sequenceNumberService.resetSequenceNumbers(sessionID);
                            } else {
                                log.debug("Received SequenceReset gap fill message (newSeqNum={})", newSeqNum);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not process SequenceReset message: {}", e.getMessage());
                }
            } else if ("A".equals(msgType)) {
                // Logon message: sync to DAS Trader's sequence number (they're the initiator)
                // Only reset to 1 if DAS Trader is explicitly sending 1
                int incomingSeqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                try {
                    Session session = Session.lookupSession(sessionID);
                    if (session != null) {
                        int expectedSeqNum = session.getExpectedTargetNum();
                        
                        // Check if DAS Trader is sending 1 (they're starting fresh)
                        boolean shouldReset = sequenceNumberService.shouldResetTo1OnLogon(
                            sessionID, incomingSeqNum, expectedSeqNum);
                        
                        if (shouldReset) {
                            // DAS Trader is sending 1 - reset to 1 to match
                            sequenceNumberService.resetSequenceNumbers(sessionID);
                            log.info("Sequence numbers reset to 1 because DAS Trader sent sequence number 1");
                        } else if (incomingSeqNum != expectedSeqNum) {
                            // DAS Trader is sending a different sequence number - sync to it
                            // This is the correct behavior for an acceptor session
                            log.info("Synchronizing to DAS Trader's sequence number: incoming={}, expected={}. " +
                                "Setting both target and sender sequence numbers to match DAS Trader.", 
                                incomingSeqNum, expectedSeqNum);
                            session.setNextTargetMsgSeqNum(incomingSeqNum);
                            
                            // Sync our sender sequence number to match what DAS Trader expects
                            // This ensures our Logon response has the correct sequence number
                            session.setNextSenderMsgSeqNum(incomingSeqNum);
                        } else {
                            log.debug("Sequence numbers already in sync: incoming={}, expected={}", incomingSeqNum, expectedSeqNum);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not adjust sequence numbers for drop copy acceptor session: {}", e.getMessage());
                }
            } else if ("5".equals(msgType)) {
                // Logout message: check if DAS Trader is telling us what sequence number they expect
                try {
                    String text = message.isSetField(quickfix.field.Text.FIELD) 
                        ? message.getString(quickfix.field.Text.FIELD) 
                        : null;
                    if (text != null && text.toLowerCase().contains("seq")) {
                        log.warn("Received Logout from DAS Trader with text that might contain sequence number info: {}", text);
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?:seq|sequence).*?(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
                        java.util.regex.Matcher matcher = pattern.matcher(text);
                        if (matcher.find()) {
                            try {
                                int expectedSeqNum = Integer.parseInt(matcher.group(1));
                                Session session = Session.lookupSession(sessionID);
                                if (session != null) {
                                    log.info("Extracted expected sender sequence number from Logout message: {}. Setting our sender sequence number to match.", expectedSeqNum);
                                    session.setNextSenderMsgSeqNum(expectedSeqNum);
                                }
                            } catch (NumberFormatException e) {
                                log.debug("Could not parse sequence number from Logout text: {}", text);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not process Logout message for sequence number info: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error processing fromAdmin for drop-copy session {}: {}", sessionID, e.getMessage());
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        // No-op for drop-copy acceptor (we only receive messages)
    }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        appMessageCount++;
        
        // Log when first app message is received
        if (firstAppMessageTime == 0) {
            firstAppMessageTime = System.currentTimeMillis();
            try {
                String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
                String msgTypeName = getMsgTypeName(msgType);
                log.warn("[DROP COPY DIAGNOSTIC] ✅ FIRST APPLICATION MESSAGE RECEIVED! Type: {} ({}), " +
                    "after {} admin messages. This confirms fromApp() is being called and DAS Trader IS sending application messages.", 
                    msgTypeName, msgType, adminMessageCount);
            } catch (Exception e) {
                log.warn("[DROP COPY DIAGNOSTIC] ✅ FIRST APPLICATION MESSAGE RECEIVED! (after {} admin messages)", adminMessageCount);
            }
        }
        
        // Log all application messages received from drop copy session for debugging
        logDropCopyMessageReceived("APP", message, sessionID);
        
        // Check if this is a Short Locate Quote Response (MsgType=S) - this should NOT come on drop copy session
        // Short Locate Quote Responses should come on the initiator session (OS111->OPAL) where the request was sent
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("S".equals(msgType)) {
                log.warn("[DROP COPY WARNING] Received Short Locate Quote Response (MsgType=S) on DROP COPY session {} (DAST->OS111). " +
                        "This is unexpected - Short Locate Quote Responses should come on the INITIATOR session (OS111->OPAL) " +
                        "where the request was sent. Message: {}", sessionID, message);
                // Still process it in case broker routing is different than expected
            }
        } catch (Exception e) {
            log.debug("Could not check message type in drop copy session: {}", e.getMessage());
        }
        
        // Track the message for REST API
        trackDropCopyMessage(sessionID, message);
        
        // Log all incoming messages from DAS Trader at INFO level
        logIncomingDASMessage(message, sessionID);

        // Crack the message for business logic processing
        try {
            crack(message, sessionID);
        } catch (UnsupportedMessageType e) {
            log.warn("[DROP COPY DEBUG] Unsupported message type in fromApp: {}", e.getMessage());
            throw e;
        } catch (IncorrectTagValue e) {
            log.warn("[DROP COPY DEBUG] Incorrect tag value in fromApp: {}", e.getMessage());
            throw e;
        } catch (FieldNotFound e) {
            log.warn("[DROP COPY DEBUG] Field not found in fromApp: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[DROP COPY DEBUG] Unexpected error in fromApp: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void onMessage(Message message, SessionID sessionID)
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);

        // Log incoming heartbeats at INFO level
        if ("0".equals(msgType)) {
            try {
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                String senderCompId = message.getHeader().getString(quickfix.field.SenderCompID.FIELD);
                String targetCompId = message.getHeader().getString(quickfix.field.TargetCompID.FIELD);
                log.info("Received heartbeat from DAS Trader ({} -> {}, seqNum={})", senderCompId, targetCompId, seqNum);
            } catch (Exception e) {
                log.warn("Could not log incoming heartbeat: {}", e.getMessage());
            }
            // Heartbeats are handled automatically by QuickFIX/J
            return;
        }

        // Handle ExecutionReports
        if (quickfix.field.MsgType.EXECUTION_REPORT.equals(msgType)) {
            log.info("Received ExecutionReport from DAS Trader: {}", message);
            executionReportProcessor.process(message, sessionID);
        } else {
            // Other message types
            crack(message, sessionID);
        }
    }

    private void trackDropCopyMessage(SessionID sessionID, Message message) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            String msgTypeName = getMsgTypeName(msgType);
            ReceivedMessage receivedMsg = new ReceivedMessage(
                sessionID.toString(),
                msgType,
                msgTypeName,
                message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD),
                java.time.Instant.now()
            );
            // Add to queue, removing oldest if full
            if (!recentDropCopyMessages.offer(receivedMsg)) {
                recentDropCopyMessages.poll(); // Remove oldest
                recentDropCopyMessages.offer(receivedMsg); // Add new one
            }
        } catch (Exception e) {
            log.debug("Could not track message: {}", e.getMessage());
        }
    }
    
    /**
     * Log all messages received from drop copy session for debugging purposes.
     * This logs every message (both admin and application) with full details.
     */
    private void logDropCopyMessageReceived(String category, Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("ADMIN".equals(category) && "0".equals(msgType)) {
                // Skip noisy heartbeat logs for admin messages
                return;
            }
            String msgTypeName = getMsgTypeName(msgType);
            int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
            String senderCompId = message.getHeader().getString(quickfix.field.SenderCompID.FIELD);
            String targetCompId = message.getHeader().getString(quickfix.field.TargetCompID.FIELD);
            
            // Log the raw message for debugging
            // Use WARN level for non-heartbeat messages to make them more visible
            if ("0".equals(msgType)) {
                // Heartbeat - log at INFO to reduce noise
                log.info("[DROP COPY DEBUG] Received {} message from drop copy session - Type: {} ({}), SeqNum: {}, Session: {} -> {}", 
                    category, msgTypeName, msgType, seqNum, senderCompId, targetCompId);
            } else {
                // Non-heartbeat messages - log at WARN to make them more visible
                log.warn("[DROP COPY DEBUG] Received {} message from drop copy session - Type: {} ({}), SeqNum: {}, Session: {} -> {}, Raw: {}", 
                    category, msgTypeName, msgType, seqNum, senderCompId, targetCompId, message.toString());
            }
        } catch (Exception e) {
            log.warn("[DROP COPY DEBUG] Could not log drop copy message details: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Log all incoming messages from DAS Trader at INFO level for visibility.
     */
    private void logIncomingDASMessage(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            String msgTypeName = getMsgTypeName(msgType);
            int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
            String senderCompId = message.getHeader().getString(quickfix.field.SenderCompID.FIELD);
            String targetCompId = message.getHeader().getString(quickfix.field.TargetCompID.FIELD);
            
            // Build detailed log message based on message type
            String details = "";
            try {
                if ("A".equals(msgType)) { // Logon
                    details = " - DAS Trader connected";
                } else if ("8".equals(msgType)) { // ExecutionReport
                    if (message.isSetField(quickfix.field.ClOrdID.FIELD)) {
                        String clOrdId = message.getString(quickfix.field.ClOrdID.FIELD);
                        String ordStatus = message.isSetField(quickfix.field.OrdStatus.FIELD) 
                            ? String.valueOf(message.getChar(quickfix.field.OrdStatus.FIELD)) 
                            : "?";
                        details = String.format(" - ClOrdID=%s, OrdStatus=%s", clOrdId, ordStatus);
                    }
                } else if ("5".equals(msgType)) { // Logout
                    if (message.isSetField(quickfix.field.Text.FIELD)) {
                        details = " - " + message.getString(quickfix.field.Text.FIELD);
                    }
                }
            } catch (Exception e) {
                // Ignore errors extracting details, just log basic info
            }
            
            log.info("DAS Trader -> {}: {} (seqNum={}, {} -> {}){}", 
                targetCompId, msgTypeName, seqNum, senderCompId, targetCompId, details);
        } catch (Exception e) {
            log.warn("Could not log incoming DAS message: {}", e.getMessage());
        }
    }
    
    private String getMsgTypeName(String msgType) {
        return switch (msgType) {
            case "0" -> "Heartbeat";
            case "1" -> "TestRequest";
            case "2" -> "ResendRequest";
            case "3" -> "Reject";
            case "4" -> "SequenceReset";
            case "5" -> "Logout";
            case "A" -> "Logon";
            case "D" -> "NewOrderSingle";
            case "8" -> "ExecutionReport";
            case "F" -> "OrderCancelRequest";
            case "G" -> "OrderCancelReplaceRequest";
            default -> "Unknown(" + msgType + ")";
        };
    }
    
    /**
     * Get recent messages received from the drop copy session.
     * @return List of recent messages (up to 100)
     */
    public List<ReceivedMessage> getRecentDropCopyMessages() {
        return new ArrayList<>(recentDropCopyMessages);
    }

    /**
     * Represents a message received from the drop copy session.
     */
    public static final class ReceivedMessage {
        private final String sessionId;
        private final String msgType;
        private final String msgTypeName;
        private final int msgSeqNum;
        private final java.time.Instant timestamp;
        
        public ReceivedMessage(String sessionId, String msgType, String msgTypeName, int msgSeqNum, java.time.Instant timestamp) {
            this.sessionId = sessionId;
            this.msgType = msgType;
            this.msgTypeName = msgTypeName;
            this.msgSeqNum = msgSeqNum;
            this.timestamp = timestamp;
        }
        
        public String getSessionId() { return sessionId; }
        public String getMsgType() { return msgType; }
        public String getMsgTypeName() { return msgTypeName; }
        public int getMsgSeqNum() { return msgSeqNum; }
        public java.time.Instant getTimestamp() { return timestamp; }
    }
}

