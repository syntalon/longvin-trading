package com.longvin.trading.processor;

import com.longvin.trading.service.DropCopyReplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.ExecType;
import quickfix.field.OrderID;
import quickfix.field.Symbol;
import quickfix.fix42.ExecutionReport;

import java.util.Map;
import java.util.Objects;

/**
 * Message processor for the drop copy acceptor session.
 * Handles messages from DAS Trader (initiator) connecting to us.
 * When ExecutionReports are received, delegates replication to DropCopyReplicationService.
 */
@Component
public class AcceptorMessageProcessor implements FixMessageProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(AcceptorMessageProcessor.class);
    
    private static final String ACCEPTOR_SENDER_COMP_ID = "OS111";
    private static final String ACCEPTOR_TARGET_COMP_ID = "DAST";
    private static final String FIX_VERSION = "FIX.4.2";
    
    private final DropCopyReplicationService replicationService;
    
    public AcceptorMessageProcessor(DropCopyReplicationService replicationService) {
        this.replicationService = Objects.requireNonNull(replicationService, "replicationService must not be null");
    }
    
    @Override
    public boolean handlesSession(SessionID sessionID) {
        return FIX_VERSION.equals(sessionID.getBeginString())
            && ACCEPTOR_SENDER_COMP_ID.equals(sessionID.getSenderCompID())
            && ACCEPTOR_TARGET_COMP_ID.equals(sessionID.getTargetCompID());
    }
    
    @Override
    public void processOutgoingAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            if ("A".equals(msgType)) {
                // For acceptor: we're sending a Logon RESPONSE
                // DO NOT modify the message - let QuickFIX/J handle it automatically
                // QuickFIX/J will respond appropriately to the incoming Logon request
                int heartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    heartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                log.info("Sending Logon response (acceptor session: {}): HeartBtInt={} seconds, message={}", 
                    sessionID, heartBtInt, message);
                // Return early - don't modify acceptor Logon responses
                return;
            } else if ("5".equals(msgType)) {
                log.info("Sending Logout (acceptor session: {}): {}", sessionID, message);
            } else if ("0".equals(msgType)) {
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.info("Sending Heartbeat (acceptor session: {}): seqNum={}", sessionID, seqNum);
            } else if ("1".equals(msgType)) {
                log.debug("Sending TestRequest (acceptor session: {})", sessionID);
            }
        } catch (Exception e) {
            log.debug("Error processing outgoing admin message for acceptor session {}: {}", sessionID, e.getMessage());
        }
    }
    
    @Override
    public void processIncomingAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            if ("A".equals(msgType)) {
                // For acceptor: we received a Logon REQUEST from the initiator (DAS Trader)
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                int heartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    heartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                log.info("Received Logon request from DAS Trader (acceptor session: {}): seqNum={}, HeartBtInt={} seconds, message={}", 
                    sessionID, seqNum, heartBtInt, message);
            } else if ("5".equals(msgType)) {
                String text = message.isSetField(quickfix.field.Text.FIELD) 
                    ? message.getString(quickfix.field.Text.FIELD) 
                    : "No reason provided";
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.warn("Received Logout from DAS Trader (acceptor session: {}): seqNum={}, reason={}", sessionID, seqNum, text);
            } else if ("0".equals(msgType)) {
                // Heartbeat message
                try {
                    int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                    boolean isTestResponse = message.isSetField(quickfix.field.TestReqID.FIELD);
                    if (isTestResponse) {
                        String testReqId = message.getString(quickfix.field.TestReqID.FIELD);
                        log.info("Received Heartbeat (TestRequest response) from DAS Trader (acceptor session: {}): seqNum={}, TestReqID={}", 
                            sessionID, seqNum, testReqId);
                    } else {
                        log.info("Received Heartbeat from DAS Trader (acceptor session: {}): seqNum={}", sessionID, seqNum);
                    }
                } catch (Exception e) {
                    log.warn("Error processing heartbeat from acceptor session {}: {}", sessionID, e.getMessage());
                }
            } else if ("1".equals(msgType)) {
                log.debug("Received TestRequest from DAS Trader (acceptor session: {})", sessionID);
            } else {
                log.debug("Received admin message {} from DAS Trader (acceptor session: {}): {}", msgType, sessionID, message);
            }
        } catch (Exception e) {
            log.debug("Error processing admin message from acceptor session {}: {}", sessionID, e.getMessage());
        }
    }
    
    @Override
    public void processIncomingApp(Message message, SessionID sessionID, Map<String, SessionID> shadowSessions) 
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            String msgTypeName = getMsgTypeName(msgType);
            int msgSeqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
            
            // Log all messages from drop copy session
            log.info("📥 Received {} from DAS Trader drop copy session {} - MsgSeqNum: {}", 
                msgTypeName, sessionID, msgSeqNum);
            
            // Handle ExecutionReport: delegate replication to service
            if ("8".equals(msgType)) { // ExecutionReport
                try {
                    ExecutionReport report = (ExecutionReport) message;
                    if (report.isSetField(ExecType.FIELD)) {
                        char execType = report.getExecType().getValue();
                        String execId = report.isSetField(quickfix.field.ExecID.FIELD) 
                            ? report.getExecID().getValue() : "N/A";
                        String orderId = report.isSetField(OrderID.FIELD) 
                            ? report.getOrderID().getValue() : "N/A";
                        String symbol = report.isSetField(Symbol.FIELD) 
                            ? report.getSymbol().getValue() : "N/A";
                        log.info("  └─ ExecutionReport details: ExecType={}, ExecID={}, OrderID={}, Symbol={}", 
                            execType, execId, orderId, symbol);
                    }
                    
                    // Delegate replication to service
                    // Find the initiator session (OS111->OPAL) to use for sending replicated orders
                    SessionID initiatorSessionID = findInitiatorSession(shadowSessions);
                    if (initiatorSessionID != null) {
                        replicationService.processExecutionReport(report, sessionID, initiatorSessionID);
                    } else {
                        log.warn("Initiator session not found; cannot replicate order. Available sessions: {}", shadowSessions.keySet());
                    }
                } catch (Exception e) {
                    log.warn("Error processing ExecutionReport: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Error processing drop copy message header: {}", e.getMessage());
        }
    }
    
    /**
     * Find the initiator session to use for sending replicated orders.
     * Looks for the session with SenderCompID=OS111 and TargetCompID=OPAL.
     */
    private SessionID findInitiatorSession(Map<String, SessionID> allSessions) {
        // Look for the initiator session (OS111->OPAL)
        for (SessionID sessionID : allSessions.values()) {
            if (ACCEPTOR_SENDER_COMP_ID.equals(sessionID.getSenderCompID()) 
                && "OPAL".equals(sessionID.getTargetCompID())
                && FIX_VERSION.equals(sessionID.getBeginString())) {
                return sessionID;
            }
        }
        return null;
    }
    
    /**
     * Get human-readable name for FIX message type.
     */
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
}

