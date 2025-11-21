package com.longvin.trading.fix;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.processor.FixMessageProcessor;
import com.longvin.trading.service.OrderReplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import quickfix.Application;
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

@Component
public class OrderReplicationCoordinator extends MessageCracker implements Application {

    private static final Logger log = LoggerFactory.getLogger(OrderReplicationCoordinator.class);

    private final FixClientProperties properties;
    private final Map<String, SessionID> sessionsBySenderCompId = new ConcurrentHashMap<>();
    private final OrderReplicationService orderReplicationService;
    private final java.util.List<FixMessageProcessor> messageProcessors;
    
    // Track recent messages from drop copy session (last 100 messages)
    private final java.util.concurrent.BlockingQueue<ReceivedMessage> recentDropCopyMessages = 
        new java.util.concurrent.LinkedBlockingQueue<>(100);

    public OrderReplicationCoordinator(FixClientProperties properties, 
                                      OrderReplicationService orderReplicationService,
                                      java.util.List<FixMessageProcessor> messageProcessors) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.orderReplicationService = Objects.requireNonNull(orderReplicationService, "orderMirroringService must not be null");
        this.messageProcessors = Objects.requireNonNull(messageProcessors, "messageProcessors must not be null");
    }

    @Override
    public void onCreate(SessionID sessionID) {
        sessionsBySenderCompId.put(sessionID.getSenderCompID(), sessionID);
        log.info("Created FIX session {}", sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        sessionsBySenderCompId.put(sessionID.getSenderCompID(), sessionID);
        log.info("Logged on to FIX session {}", sessionID);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        sessionsBySenderCompId.remove(sessionID.getSenderCompID());
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                log.warn("Logged out from FIX session {} - Session state: isLoggedOn={}, isEnabled={}", 
                    sessionID, session.isLoggedOn(), session.isEnabled());
            } else {
                log.warn("Logged out from FIX session {} - Session object not found", sessionID);
            }
        } catch (Exception e) {
            log.warn("Logged out from FIX session {} - Error getting session details: {}", sessionID, e.getMessage());
        }
        log.warn("Logged out from FIX session {} - this may indicate a connection issue or server-side timeout", sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        // Delegate to the appropriate processor
        for (FixMessageProcessor processor : messageProcessors) {
            if (processor.handlesSession(sessionID)) {
                processor.processOutgoingAdmin(message, sessionID);
                return;
            }
        }
        // If no processor handles this session, log a warning
        log.debug("No processor found for session {}, using default handling", sessionID);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) {
        // Delegate to the appropriate processor
        for (FixMessageProcessor processor : messageProcessors) {
            if (processor.handlesSession(sessionID)) {
                processor.processIncomingAdmin(message, sessionID);
                return;
            }
        }
        // If no processor handles this session, log a warning
        log.debug("No processor found for session {}, using default handling", sessionID);
    }

    @Override
    public void toApp(Message message, SessionID sessionID) {
        // no-op
    }

    @Override
    public void fromApp(Message message, SessionID sessionID) throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        // Delegate to the appropriate processor for logging/tracking
        for (FixMessageProcessor processor : messageProcessors) {
            if (processor.handlesSession(sessionID)) {
                processor.processIncomingApp(message, sessionID, sessionsBySenderCompId);
                
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
    
    private boolean isDropCopySession(SessionID sessionID) {
        return "OS111".equals(sessionID.getSenderCompID()) 
            && "DAST".equals(sessionID.getTargetCompID())
            && "FIX.4.2".equals(sessionID.getBeginString());
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

    @Override
    public void onMessage(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        // Drop copy ExecutionReports are now handled by AcceptorMessageProcessor via DropCopyReplicationService
        // This method is kept for any other ExecutionReport handling that might be needed
        // (e.g., from initiator sessions for order status updates)
        log.trace("Received ExecutionReport on session {}: {}", sessionID, report);
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
