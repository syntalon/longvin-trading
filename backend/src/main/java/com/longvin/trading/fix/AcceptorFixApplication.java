package com.longvin.trading.fix;

import java.util.Objects;

import org.springframework.stereotype.Component;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

@Component
public class AcceptorFixApplication implements Application {

    private static final String CONNECTION_TYPE = "acceptor";

    private final OrderReplicationCoordinator coordinator;
    private final FixSessionRegistry sessionRegistry;

    public AcceptorFixApplication(OrderReplicationCoordinator coordinator,
                                 FixSessionRegistry sessionRegistry) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
    }

    @Override
    public void onCreate(SessionID sessionID) {
        coordinator.onCreate(sessionID, CONNECTION_TYPE);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        sessionRegistry.register(CONNECTION_TYPE, sessionID);
        coordinator.onLogon(sessionID, CONNECTION_TYPE);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        sessionRegistry.unregister(CONNECTION_TYPE, sessionID);
        coordinator.onLogout(sessionID, CONNECTION_TYPE);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        coordinator.toAdmin(message, sessionID, CONNECTION_TYPE);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) throws FieldNotFound, IncorrectTagValue, RejectLogon {
        coordinator.fromAdmin(message, sessionID, CONNECTION_TYPE);
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        // no-op
    }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectTagValue, UnsupportedMessageType {
        coordinator.fromApp(message, sessionID, CONNECTION_TYPE);
    }
}
