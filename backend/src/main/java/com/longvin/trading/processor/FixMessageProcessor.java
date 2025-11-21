package com.longvin.trading.processor;

import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

import java.util.Map;

/**
 * Interface for processing FIX messages for a specific session type.
 */
public interface FixMessageProcessor {
    
    /**
     * Process an outgoing admin message before it's sent.
     * @param message The message being sent
     * @param sessionID The session ID
     */
    void processOutgoingAdmin(Message message, SessionID sessionID);
    
    /**
     * Process an incoming admin message after it's received.
     * @param message The message received
     * @param sessionID The session ID
     */
    void processIncomingAdmin(Message message, SessionID sessionID);
    
    /**
     * Process an incoming application message after it's received.
     * This is called before the message is cracked/dispatched.
     * @param message The message received
     * @param sessionID The session ID
     * @param shadowSessions Map of shadow session IDs by sender comp ID (for replication)
     * @throws FieldNotFound if a required field is missing
     * @throws UnsupportedMessageType if the message type is not supported
     * @throws IncorrectTagValue if a field value is incorrect
     */
    default void processIncomingApp(Message message, SessionID sessionID, Map<String, SessionID> shadowSessions) 
            throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        // Default: no-op, processors can override if needed
    }
    
    /**
     * Process an outgoing application message before it's sent.
     * @param message The message being sent
     * @param sessionID The session ID
     */
    default void processOutgoingApp(Message message, SessionID sessionID) {
        // Default: no-op, processors can override if needed
    }
    
    /**
     * Check if this processor handles the given session.
     * @param sessionID The session ID to check
     * @return true if this processor handles this session
     */
    boolean handlesSession(SessionID sessionID);
}

