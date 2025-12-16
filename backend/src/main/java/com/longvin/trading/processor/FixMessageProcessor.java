package com.longvin.trading.processor;

import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

import com.longvin.trading.fix.FixSessionRegistry;

/**
 * Interface for processing FIX messages for a specific session type.
 */
public interface FixMessageProcessor {
    
    /**
     * Process an outgoing admin message before it's sent.
     * @param message The message being sent
     * @param sessionID The session ID
     * @throws DoNotSend if the message should not be sent
     */
    void processOutgoingAdmin(Message message, SessionID sessionID) throws DoNotSend;
    
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
     * @param sessionRegistry Session registry for looking up other sessions (for replication)
     * @throws FieldNotFound if a required field is missing
     * @throws UnsupportedMessageType if the message type is not supported
     * @throws IncorrectTagValue if a field value is incorrect
     */
    default void processIncomingApp(Message message, SessionID sessionID, FixSessionRegistry sessionRegistry)
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
    
    /**
     * Check if this processor handles the given session based on connection type.
     * @param sessionID The session ID to check
     * @param connectionType The connection type ("acceptor" or "initiator")
     * @return true if this processor handles the session
     */
    default boolean handlesSession(SessionID sessionID, String connectionType) {
        return handlesSession(sessionID);
    }
}

