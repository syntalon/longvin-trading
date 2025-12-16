package com.longvin.trading.fix;

import org.springframework.stereotype.Component;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class FixSessionRegistry {

    private final ConcurrentMap<FixSessionKey, SessionID> sessions = new ConcurrentHashMap<>();

    public void register(String connectionType, SessionID sessionID) {
        FixSessionKey key = FixSessionKey.from(connectionType, sessionID);
        sessions.put(key, sessionID);
    }

    public void unregister(String connectionType, SessionID sessionID) {
        FixSessionKey key = FixSessionKey.from(connectionType, sessionID);
        sessions.remove(key, sessionID);
    }

    public Optional<SessionID> find(FixSessionKey key) {
        return Optional.ofNullable(sessions.get(key));
    }

    public Optional<SessionID> findLoggedOn(FixSessionKey key) {
        SessionID sessionID = sessions.get(key);
        if (sessionID == null) {
            return Optional.empty();
        }
        Session session = Session.lookupSession(sessionID);
        if (session == null || !session.isLoggedOn()) {
            return Optional.empty();
        }
        return Optional.of(sessionID);
    }

    public void sendToTarget(FixSessionKey key, quickfix.Message message) throws SessionNotFound {
        SessionID sessionID = findLoggedOn(key).orElseThrow(() -> new SessionNotFound("No logged-on session for key " + key));
        Session.sendToTarget(message, sessionID);
    }

    public Optional<SessionID> findAnyLoggedOnInitiator() {
        for (Map.Entry<FixSessionKey, SessionID> entry : sessions.entrySet()) {
            FixSessionKey key = entry.getKey();
            if (key == null || key.getConnectionType() == null) {
                continue;
            }
            if (!"initiator".equalsIgnoreCase(key.getConnectionType())) {
                continue;
            }
            SessionID sessionID = entry.getValue();
            Session session = Session.lookupSession(sessionID);
            if (session != null && session.isLoggedOn()) {
                return Optional.of(sessionID);
            }
        }
        return Optional.empty();
    }

    public Optional<SessionID> findLoggedOnInitiatorByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        String needle = alias.trim();
        for (Map.Entry<FixSessionKey, SessionID> entry : sessions.entrySet()) {
            FixSessionKey key = entry.getKey();
            if (key == null || key.getConnectionType() == null) {
                continue;
            }
            if (!"initiator".equalsIgnoreCase(key.getConnectionType())) {
                continue;
            }
            if (needle.equalsIgnoreCase(key.getSenderCompId())
                    || needle.equalsIgnoreCase(key.getTargetCompId())
                    || (key.getSessionQualifier() != null && needle.equalsIgnoreCase(key.getSessionQualifier()))) {
                SessionID sessionID = entry.getValue();
                Session session = Session.lookupSession(sessionID);
                if (session != null && session.isLoggedOn()) {
                    return Optional.of(sessionID);
                }
            }
        }
        return Optional.empty();
    }
}
