package com.longvin.trading.fix;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.processor.FixMessageProcessor;
import com.longvin.trading.processor.FixMessageProcessorFactory;
import com.longvin.trading.fix.FixSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.Account;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.MessageCracker;
import quickfix.fix42.NewOrderSingle;

/**
 * Shared coordinator used by both acceptor and initiator QuickFIX/J Applications.
 */
@Component
public class OrderReplicationCoordinator extends MessageCracker {

    private static final Logger log = LoggerFactory.getLogger(OrderReplicationCoordinator.class);

    private final FixClientProperties properties;
    private final FixMessageProcessorFactory processorFactory;
    private final FixSessionRegistry sessionRegistry;
    
    // Track recent messages from drop copy session (last 100 messages)
    private final java.util.concurrent.BlockingQueue<ReceivedMessage> recentDropCopyMessages = 
        new java.util.concurrent.LinkedBlockingQueue<>(100);

    public OrderReplicationCoordinator(FixClientProperties properties,
                                       FixMessageProcessorFactory processorFactory,
                                       FixSessionRegistry sessionRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.processorFactory = Objects.requireNonNull(processorFactory, "processorFactory must not be null");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
    }

    public void onCreate(SessionID sessionID, String connectionType) {
        log.info("Created FIX session {} (connection type: {})", sessionID, connectionType);
        
        // For drop copy acceptor sessions, set target sequence number to 1 initially
        // This allows QuickFIX/J to accept any incoming sequence number >= 1 from DAS Trader
        // The actual value will be synchronized in fromAdmin when Logon is received
        // Sender sequence number is managed by FileStore (persists across restarts)
        if (isDropCopySession(sessionID)) {
            try {
                Session session = Session.lookupSession(sessionID);
                if (session != null) {
                    // Set target sequence number to 1 to accept any incoming sequence number >= 1
                    // QuickFIX/J will accept DAS Trader's sequence numbers (like 634) without validation errors
                    // The actual value will be synchronized in fromAdmin
                    session.setNextTargetMsgSeqNum(1);
                    log.info("Drop copy acceptor session created {} - set targetSeqNum=1 initially to accept any incoming sequence number. " +
                        "Will synchronize to actual value in fromAdmin. Sender sequence number managed by FileStore.", sessionID);
                }
            } catch (Exception e) {
                log.debug("Could not set target sequence number for drop copy acceptor session {}: {}", sessionID, e.getMessage());
            }
        }
    }

    public void onLogon(SessionID sessionID, String connectionType) {
        log.info("Logged on to FIX session {}", sessionID);
        // For drop copy acceptor sessions, synchronize sequence numbers after logon
        // This ensures we're in sync with DAS Trader's sequence numbers
        if (isDropCopySession(sessionID)) {
            try {
                Session session = Session.lookupSession(sessionID);
                if (session != null) {
                    int expectedSeqNum = session.getExpectedTargetNum();
                    log.info("Drop copy session logged on - current expected sequence number: {}", expectedSeqNum);
                }
            } catch (Exception e) {
                log.debug("Could not check sequence number after logon for drop copy session {}: {}", sessionID, e.getMessage());
            }
        }
    }

    public void onLogout(SessionID sessionID, String connectionType) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                int targetSeqNum = session.getExpectedTargetNum();
                log.warn("Logged out from FIX session {} - Session state: isLoggedOn={}, isEnabled={}, expectedTargetSeqNum={}", 
                    sessionID, session.isLoggedOn(), session.isEnabled(), targetSeqNum);
                
                // For drop copy sessions, log additional details
                if (isDropCopySession(sessionID)) {
                    log.warn("Drop copy session logout - DAS Trader may have rejected our Logon response or closed the connection. " +
                        "Check if DAS Trader sent a Logout message (should appear in logs above). " +
                        "If no Logout message is visible, DAS Trader may have closed the connection without sending one.");
                }
            } else {
                log.warn("Logged out from FIX session {} - Session object not found", sessionID);
            }
        } catch (Exception e) {
            log.warn("Logged out from FIX session {} - Error getting session details: {}", sessionID, e.getMessage());
        }
        log.warn("Logged out from FIX session {} - this may indicate a connection issue or server-side timeout", sessionID);
    }

    public void toAdmin(Message message, SessionID sessionID, String connectionType) {
        java.util.List<FixMessageProcessor> processors = processorFactory.getProcessorsForSession(sessionID, connectionType);
        
        for (FixMessageProcessor processor : processors) {
            boolean handles = processor.handlesSession(sessionID, connectionType);
            
            if (handles) {
                try {
                    processor.processOutgoingAdmin(message, sessionID);
                    return;
                } catch (quickfix.DoNotSend e) {
                    // Re-throw DoNotSend wrapped in RuntimeException since Application.toAdmin doesn't declare it
                    // QuickFIX/J framework will catch and handle it
                    throw new RuntimeException("DoNotSend", e);
                } catch (Exception e) {
                    log.debug("Error in processor {} for session {}: {}", processor.getClass().getSimpleName(), sessionID, e.getMessage());
                    return;
                }
            }
        }
        log.debug("No processor found for session {} (connection type: {}), using default handling", sessionID, connectionType);
    }

    public void fromAdmin(Message message, SessionID sessionID, String connectionType) {
        // Handle sequence number synchronization for drop copy acceptor sessions
        // IMPORTANT: QuickFIX/J validates sequence numbers BEFORE calling fromAdmin
        // If validation fails, we won't get here. To handle sequence number gaps,
        // we need to set the expected sequence number in onCreate or use a custom MessageFactory
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if (isDropCopySession(sessionID)) {
                if ("A".equals(msgType)) {
                    // Logon message: synchronize both target and sender sequence numbers
                    int incomingSeqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                    try {
                        Session session = Session.lookupSession(sessionID);
                        if (session != null) {
                            // Get current expected sequence number (what we expect to receive next from DAS Trader)
                            int expectedSeqNum = session.getExpectedTargetNum();
                            // Always synchronize to DAS Trader's sequence number for Logon messages
                            // This ensures we're in sync for all subsequent messages
                            if (incomingSeqNum != expectedSeqNum) {
                                log.info("Synchronizing target sequence numbers: incoming={}, expected={}. Setting expected sequence number to match DAS Trader.", 
                                    incomingSeqNum, expectedSeqNum);
                                // Set our expected sequence number to match what DAS Trader is sending
                                // This tells QuickFIX/J to accept messages starting from this sequence number
                                session.setNextTargetMsgSeqNum(incomingSeqNum);
                            }
                            
                            // Try to synchronize our sender sequence number to match DAS Trader's sender sequence number
                            // This is unusual but some FIX implementations expect sender sequence numbers to be synchronized
                            // DAS Trader's sender sequence number (incomingSeqNum) is what they're sending to us
                            // We'll set our sender sequence number to match theirs
                            // This will be used in the Logon response that QuickFIX/J sends
                            // The FileStore will persist this value
                            log.info("Setting our sender sequence number to match DAS Trader's sender sequence number: {}. " +
                                "This will be used in the Logon response and persisted in FileStore.", incomingSeqNum);
                            session.setNextSenderMsgSeqNum(incomingSeqNum);
                        }
                    } catch (Exception e) {
                        log.debug("Could not adjust sequence numbers for drop copy acceptor session: {}", e.getMessage());
                    }
                } else if ("5".equals(msgType)) {
                    // Logout message: check if DAS Trader is telling us what sequence number they expect
                    // Some FIX implementations include the expected sequence number in the Logout Text field
                    try {
                        String text = message.isSetField(quickfix.field.Text.FIELD) 
                            ? message.getString(quickfix.field.Text.FIELD) 
                            : null;
                        if (text != null && text.toLowerCase().contains("seq")) {
                            log.warn("Received Logout from DAS Trader with text that might contain sequence number info: {}", text);
                            // Try to extract sequence number from text (format varies by implementation)
                            // Example: "Expected sequence number: 21" or "SeqNum mismatch: expected 21"
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
                        log.debug("Could not process Logout message for sequence number info: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error checking sequence numbers in fromAdmin: {}", e.getMessage());
        }
        
        // Delegate to the appropriate processor based on connection type
        java.util.List<FixMessageProcessor> processors = processorFactory.getProcessorsForSession(sessionID, connectionType);
        
        for (FixMessageProcessor processor : processors) {
            boolean handles = processor.handlesSession(sessionID, connectionType);
            
            if (handles) {
                processor.processIncomingAdmin(message, sessionID);
                return;
            }
        }
        log.debug("No processor found for session {} (connection type: {}), using default handling", sessionID, connectionType);
    }

    public void fromApp(Message message, SessionID sessionID, String connectionType)
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        // Delegate to the appropriate processor based on connection type
        java.util.List<FixMessageProcessor> processors = processorFactory.getProcessorsForSession(sessionID, connectionType);
        
        for (FixMessageProcessor processor : processors) {
            boolean handles = processor.handlesSession(sessionID, connectionType);
            
            if (handles) {
                processor.processIncomingApp(message, sessionID, sessionRegistry);
                
                // Track the message for drop copy session (for REST API)
                if (isDropCopySession(sessionID)) {
                    trackDropCopyMessage(sessionID, message);
                }
                break;
            }
        }
        
        // Always crack the message for business logic processing
        crack(message, sessionID);
    }
    
    /**
     * Check if the given session ID matches the drop copy acceptor session configuration.
     * Uses properties instead of hardcoded values.
     */
    private boolean isDropCopySession(SessionID sessionID) {
        return "FIX.4.2".equals(sessionID.getBeginString())
            && properties.getDropCopySessionSenderCompId().equals(sessionID.getSenderCompID())
            && properties.getDropCopySessionTargetCompId().equals(sessionID.getTargetCompID());
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
    public java.util.List<ReceivedMessage> getRecentDropCopyMessages() {
        return new java.util.ArrayList<>(recentDropCopyMessages);
    }

    public NewOrderSingle cloneOrder(NewOrderSingle order) throws CloneNotSupportedException {
        return (NewOrderSingle) order.clone();
    }

    public void overrideAccountIfNeeded(Message order, String shadowSenderCompId) {
        Map<String, String> overrides = properties.getShadowAccounts();
        if (overrides.isEmpty()) {
            return;
        }
        Optional.ofNullable(overrides.get(shadowSenderCompId)).ifPresent(account -> order.setField(new Account(account)));
    }

    public String generateMirrorClOrdId(String shadowSenderCompId, String source, String action) {
        String base = properties.getClOrdIdPrefix() + action + "-" + shadowSenderCompId + "-" + source;
        if (base.length() > 19) {
            return base.substring(base.length() - 19);
        }
        return base;
    }

    public String getSafeClOrdId(NewOrderSingle order) {
        try {
            return order.getClOrdID().getValue();
        } catch (FieldNotFound e) {
            return "<missing>";
        }
    }

    /**
     * Get the SessionID for a given senderCompId.
     * @param senderCompId the FIX SenderCompID
     * @return Optional containing the SessionID if found and logged on, empty otherwise
     */
    public Optional<SessionID> getSessionIdForSenderCompId(String senderCompId) {
        return sessionRegistry.findLoggedOnInitiatorByAlias(senderCompId);
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
