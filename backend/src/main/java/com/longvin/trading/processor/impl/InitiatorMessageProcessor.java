package com.longvin.trading.processor.impl;

import com.longvin.trading.fix.FixSessionManager;
import com.longvin.trading.fix.InitiatorLogonGuard;
import com.longvin.trading.processor.FixMessageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

import java.util.Map;

/**
 * Message processor for the order-entry initiator session.
 * Handles messages when we connect to the trading server.
 * This is a stateless POJO created by FixMessageProcessorFactory.
 */
public class InitiatorMessageProcessor implements FixMessageProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(InitiatorMessageProcessor.class);
    
    private static final String INITIATOR_SENDER_COMP_ID = "OS111";
    private static final String INITIATOR_TARGET_COMP_ID = "OPAL";
    private static final String FIX_VERSION = "FIX.4.2";
    
    private final InitiatorLogonGuard logonGuard;
    private final FixSessionManager sessionManager;
    private final LocateResponseHandler locateResponseHandler;
    
    public InitiatorMessageProcessor(InitiatorLogonGuard logonGuard, 
                                     FixSessionManager sessionManager,
                                     LocateResponseHandler locateResponseHandler) {
        this.logonGuard = logonGuard;
        this.sessionManager = sessionManager;
        this.locateResponseHandler = locateResponseHandler;
    }
    
    @Override
    public boolean handlesSession(SessionID sessionID) {
        return FIX_VERSION.equals(sessionID.getBeginString())
            && INITIATOR_SENDER_COMP_ID.equals(sessionID.getSenderCompID())
            && INITIATOR_TARGET_COMP_ID.equals(sessionID.getTargetCompID());
    }
    
    @Override
    public boolean handlesSession(SessionID sessionID, String connectionType) {
        return "initiator".equalsIgnoreCase(connectionType);
    }
    
    @Override
    public void processOutgoingAdmin(Message message, SessionID sessionID) throws DoNotSend {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            if ("A".equals(msgType)) {
                if (!logonGuard.isLogonAllowed(sessionID)) {
                    log.info("Logon attempt suppressed for session {} until {}", sessionID, logonGuard.getNextAllowedLogonFormatted());
                    throw new DoNotSend();
                }
                if (!message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD)) {
                    message.setField(new quickfix.field.ResetSeqNumFlag(true));
                }
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
                if (text != null && text.toLowerCase().contains("not trade day")) {
                    logonGuard.markNotTradingDay(sessionID, text);
                    // Pause the initiator to prevent repeated logon attempts
                    sessionManager.pauseInitiator(text);
                    // Schedule resume for the next trading window
                    logonGuard.scheduleResume(() -> sessionManager.resumeInitiatorIfPaused());
                }
            } else if ("0".equals(msgType)) {
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
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            
            // Handle locate responses (MsgType="M" or custom)
            if ("M".equals(msgType)) {
                log.debug("Received locate response on initiator session {}", sessionID);
                locateResponseHandler.processLocateResponse(message, sessionID);
            }
            // Other application messages can be handled here if needed
        } catch (Exception e) {
            log.debug("Error processing application message from initiator session {}: {}", sessionID, e.getMessage());
        }
    }
}

