package com.longvin.trading.fix;

import java.util.Objects;

import org.springframework.stereotype.Service;

import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;

@Service
public class FixGatewayService {

    private final FixSessionRegistry sessionRegistry;

    public FixGatewayService(FixSessionRegistry sessionRegistry) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
    }

    public SessionID requireAnyLoggedOnInitiatorSession() throws SessionNotFound {
        return sessionRegistry.findAnyLoggedOnInitiator()
                .orElseThrow(() -> new SessionNotFound("No logged-on initiator session"));
    }

    public SessionID sendToAnyLoggedOnInitiator(Message message) throws SessionNotFound {
        SessionID sessionID = requireAnyLoggedOnInitiatorSession();
        Session.sendToTarget(message, sessionID);
        return sessionID;
    }

    public SessionID sendTo(Message message, FixSessionKey key) throws SessionNotFound {
        SessionID sessionID = sessionRegistry.findLoggedOn(key)
                .orElseThrow(() -> new SessionNotFound("No logged-on session for key " + key));
        Session.sendToTarget(message, sessionID);
        return sessionID;
    }
}
