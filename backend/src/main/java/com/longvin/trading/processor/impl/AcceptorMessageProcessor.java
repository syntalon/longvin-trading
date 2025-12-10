package com.longvin.trading.processor.impl;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.processor.FixMessageProcessor;
import com.longvin.trading.service.DropCopyReplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;

/**
 * Message processor for the drop copy acceptor session.
 * Handles messages from DAS Trader (initiator) connecting to us.
 * When ExecutionReports are received, delegates replication to DropCopyReplicationService.
 * This is a stateless POJO created by FixMessageProcessorFactory.
 */
public class AcceptorMessageProcessor implements FixMessageProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(AcceptorMessageProcessor.class);
    
    private static final String FIX_VERSION = "FIX.4.2";
    
    private final DropCopyReplicationService replicationService;
    private final FixClientProperties properties;
    
    public AcceptorMessageProcessor(DropCopyReplicationService replicationService,
                                    FixClientProperties properties) {
        this.replicationService = Objects.requireNonNull(replicationService, "replicationService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }
    
    @Override
    public boolean handlesSession(SessionID sessionID) {
        return FIX_VERSION.equals(sessionID.getBeginString())
            && properties.getDropCopySessionSenderCompId().equals(sessionID.getSenderCompID())
            && properties.getDropCopySessionTargetCompId().equals(sessionID.getTargetCompID());
    }
    
    @Override
    public boolean handlesSession(SessionID sessionID, String connectionType) {
        // Acceptor processor handles acceptor sessions
        return "acceptor".equalsIgnoreCase(connectionType);
    }
    
    @Override
    public void processOutgoingAdmin(Message message, SessionID sessionID) throws quickfix.DoNotSend {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            if ("A".equals(msgType)) {
                // For acceptor: we're sending a Logon RESPONSE
                // DO NOT modify the message - let QuickFIX/J handle sequence number synchronization automatically
                // QuickFIX/J will accept DAS Trader's sequence numbers and synchronize accordingly
                int heartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    heartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                int ourSeqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                boolean hasResetFlag = message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD)
                    && message.getBoolean(quickfix.field.ResetSeqNumFlag.FIELD);
                
                // Log both our sender and expected target sequence numbers
                try {
                    quickfix.Session session = quickfix.Session.lookupSession(sessionID);
                    if (session != null) {
                        int expectedTargetSeqNum = session.getExpectedTargetNum();
                        log.info("Sending Logon response (acceptor session: {}): ourSeqNum={}, expectedTargetSeqNum={}, HeartBtInt={} seconds, ResetSeqNumFlag={}, message={}", 
                            sessionID, ourSeqNum, expectedTargetSeqNum, heartBtInt, hasResetFlag, message);
                    } else {
                        log.info("Sending Logon response (acceptor session: {}): seqNum={}, HeartBtInt={} seconds, ResetSeqNumFlag={}, message={}", 
                            sessionID, ourSeqNum, heartBtInt, hasResetFlag, message);
                    }
                } catch (Exception e) {
                    log.info("Sending Logon response (acceptor session: {}): seqNum={}, HeartBtInt={} seconds, ResetSeqNumFlag={}, message={}",
                        sessionID, ourSeqNum, heartBtInt, hasResetFlag, message);
                    log.debug("Error getting session details for logging: {}", e.getMessage());
                }
                // Return early - don't modify acceptor Logon responses
                return;
            } else if ("5".equals(msgType)) {
                log.info("Sending Logout (acceptor session: {}): {}", sessionID, message);
            } else if ("0".equals(msgType)) {
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                //log.info("Sending Heartbeat (acceptor session: {}): seqNum={}", sessionID, seqNum);
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
                boolean hasResetFlag = message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD) 
                    && message.getBoolean(quickfix.field.ResetSeqNumFlag.FIELD);
                log.info("Received Logon request from DAS Trader (acceptor session: {}): seqNum={}, HeartBtInt={} seconds, ResetSeqNumFlag={}, message={}",
                   sessionID, seqNum, heartBtInt, hasResetFlag, message);
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
                       // log.info("Received Heartbeat (TestRequest response) from DAS Trader (acceptor session: {}): seqNum={}, TestReqID={}",
                        //    sessionID, seqNum, testReqId);
                    } else {
                        //log.info("Received Heartbeat from DAS Trader (acceptor session: {}): seqNum={}", sessionID, seqNum);
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
            log.info("shadowSessions={}", shadowSessions);
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
                    
                    Optional<SessionID> initiatorSession = findInitiatorSession(sessionID, shadowSessions);
                    if (initiatorSession.isPresent()) {
                        replicationService.processExecutionReport(report, sessionID, initiatorSession.get());
                    } else {
                        log.warn("No initiator session found to replicate ExecutionReport for session {}", sessionID);
                    }
                } catch (ClassCastException e) {
                    log.warn("Received message marked as ExecutionReport but unable to cast. Message: {}", message, e);
                }
            }
        } catch (Exception e) {
            log.error("Error processing drop copy application message: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void processOutgoingApp(Message message, SessionID sessionID) {
        // Logging for outgoing application messages (if any) can be added here when needed
    }
    
    private Optional<SessionID> findInitiatorSession(SessionID dropCopySessionID, Map<String, SessionID> shadowSessions) {
        return Optional.ofNullable(shadowSessions.get(properties.getPrimarySession()));
    }
    
    private String getMsgTypeName(String msgType) {
        return switch (msgType) {
            case "0" -> "Heartbeat";
            case "1" -> "TestRequest";
            case "2" -> "ResendRequest";
            case "3" -> "Reject";
            case "4" -> "SequenceReset";
            case "5" -> "Logout";
            case "8" -> "ExecutionReport";
            default -> "Unknown (" + msgType + ")";
        };
    }
}

