package com.longvin.trading.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

import java.util.Map;

/**
 * Message processor for the order-entry initiator session.
 * Handles messages when we connect to the trading server.
 */
@Component
public class InitiatorMessageProcessor implements FixMessageProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(InitiatorMessageProcessor.class);
    
    private static final String INITIATOR_SENDER_COMP_ID = "OS111";
    private static final String INITIATOR_TARGET_COMP_ID = "OPAL";
    private static final String FIX_VERSION = "FIX.4.2";
    
    @Override
    public boolean handlesSession(SessionID sessionID) {
        return FIX_VERSION.equals(sessionID.getBeginString())
            && INITIATOR_SENDER_COMP_ID.equals(sessionID.getSenderCompID())
            && INITIATOR_TARGET_COMP_ID.equals(sessionID.getTargetCompID());
    }
    
    @Override
    public void processOutgoingAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            if ("A".equals(msgType)) {
                // For initiator: we're sending a Logon REQUEST
                // Add ResetSeqNumFlag to reset sequence numbers on logon
                // This helps when there's a sequence number mismatch with the server
                if (!message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD)) {
                    message.setField(new quickfix.field.ResetSeqNumFlag(true));
                }
                // Extract our heartbeat interval to log it
                int clientHeartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    clientHeartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                log.info("Sending Logon request (initiator session: {}): Client HeartBtInt={} seconds, message={}", 
                    sessionID, clientHeartBtInt, message);
            } else if ("5".equals(msgType)) {
                log.info("Sending Logout (initiator session: {}): {}", sessionID, message);
            } else if ("0".equals(msgType)) {
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.info("Sending Heartbeat (initiator session: {}): seqNum={}", sessionID, seqNum);
            } else if ("1".equals(msgType)) {
                log.debug("Sending TestRequest (initiator session: {})", sessionID);
            }
        } catch (Exception e) {
            log.debug("Error processing outgoing admin message for initiator session {}: {}", sessionID, e.getMessage());
        }
    }
    
    @Override
    public void processIncomingAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            if ("A".equals(msgType)) {
                // For initiator: we received a Logon RESPONSE from the server
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                int serverHeartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    serverHeartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                log.info("Received Logon response from server (initiator session: {}): seqNum={}, Server HeartBtInt={} seconds, message={}", 
                    sessionID, seqNum, serverHeartBtInt, message);
            } else if ("5".equals(msgType)) {
                String text = message.isSetField(quickfix.field.Text.FIELD) 
                    ? message.getString(quickfix.field.Text.FIELD) 
                    : "No reason provided";
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.warn("Received Logout from server (initiator session: {}): seqNum={}, reason={}", sessionID, seqNum, text);
            } else if ("0".equals(msgType)) {
                // Heartbeat message
                try {
                    int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                    boolean isTestResponse = message.isSetField(quickfix.field.TestReqID.FIELD);
                    if (isTestResponse) {
                        String testReqId = message.getString(quickfix.field.TestReqID.FIELD);
                        log.info("Received Heartbeat (TestRequest response) from server (initiator session: {}): seqNum={}, TestReqID={}", 
                            sessionID, seqNum, testReqId);
                    } else {
                        log.info("Received Heartbeat from server (initiator session: {}): seqNum={}", sessionID, seqNum);
                    }
                } catch (Exception e) {
                    log.warn("Error processing heartbeat from initiator session {}: {}", sessionID, e.getMessage());
                }
            } else if ("1".equals(msgType)) {
                log.debug("Received TestRequest from server (initiator session: {})", sessionID);
            } else {
                log.debug("Received admin message {} from server (initiator session: {}): {}", msgType, sessionID, message);
            }
        } catch (Exception e) {
            log.debug("Error processing admin message from initiator session {}: {}", sessionID, e.getMessage());
        }
    }
    
    @Override
    public void processIncomingApp(Message message, SessionID sessionID, Map<String, SessionID> shadowSessions) 
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        // Initiator processor doesn't need to handle application messages for replication
        // (that's handled by AcceptorMessageProcessor for drop copy)
    }
}

