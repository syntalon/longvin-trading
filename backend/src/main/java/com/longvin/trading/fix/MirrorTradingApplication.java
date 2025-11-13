package com.longvin.trading.fix;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import quickfix.Application;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.MsgType;
import quickfix.fix44.MessageCracker;
import quickfix.fix44.NewOrderSingle;

@Component
public class MirrorTradingApplication extends MessageCracker implements Application {

    private static final Logger log = LoggerFactory.getLogger(MirrorTradingApplication.class);

    private final FixClientProperties properties;
    private final Map<String, SessionID> sessionsBySenderCompId = new ConcurrentHashMap<>();

    public MirrorTradingApplication(FixClientProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
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
        log.info("Logged out from FIX session {}", sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        // no-op
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) {
        // no-op
    }

    @Override
    public void toApp(Message message, SessionID sessionID) {
        // no-op
    }

    @Override
    public void fromApp(Message message, SessionID sessionID) throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
        crack(message, sessionID);
    }

    @Override
    public void onMessage(NewOrderSingle order, SessionID sessionID) throws FieldNotFound {
        String senderCompId = sessionID.getSenderCompID();
        if (!senderCompId.equalsIgnoreCase(properties.getPrimarySession())) {
            return;
        }

        if (properties.getShadowSessions().isEmpty()) {
            log.debug("No shadow sessions configured; skipping mirror for {}", order);
            return;
        }

        for (String shadowSenderCompId : properties.getShadowSessions()) {
            SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror order {}", shadowSenderCompId,
                        getSafeClOrdId(order));
                continue;
            }

            try {
                NewOrderSingle mirroredOrder = cloneOrder(order);
                overrideAccountIfNeeded(mirroredOrder, shadowSenderCompId);
                mirroredOrder.set(new ClOrdID(generateMirrorClOrdId(shadowSenderCompId)));
                Session.sendToTarget(mirroredOrder, shadowSession);
                log.info("Mirrored order {} from {} to {}", getSafeClOrdId(order), senderCompId, shadowSenderCompId);
            } catch (CloneNotSupportedException | SessionNotFound ex) {
                log.error("Failed to mirror order {} to {}, reason: {}", getSafeClOrdId(order), shadowSenderCompId,
                        ex.getMessage(), ex);
            }
        }
    }

    public void onMessage(Message message, SessionID sessionID) {
        try {
            if (MsgType.ORDER_CANCEL_REPLACE_REQUEST.equals(message.getHeader().getString(MsgType.FIELD))
                    || MsgType.ORDER_CANCEL_REQUEST.equals(message.getHeader().getString(MsgType.FIELD))) {
                log.debug("Received {} from {}; add handling if cancels should mirror", message.getClass().getSimpleName(),
                        sessionID);
            }
        } catch (FieldNotFound e) {
            log.warn("Received message without MsgType from {}: {}", sessionID, message, e);
        }
    }

    private NewOrderSingle cloneOrder(NewOrderSingle order) throws CloneNotSupportedException {
        return (NewOrderSingle) order.clone();
    }

    private void overrideAccountIfNeeded(NewOrderSingle order, String shadowSenderCompId) {
        Map<String, String> overrides = properties.getShadowAccounts();
        if (overrides.isEmpty()) {
            return;
        }
        Optional.ofNullable(overrides.get(shadowSenderCompId))
                .ifPresent(account -> order.setField(new Account(account)));
    }

    private String generateMirrorClOrdId(String shadowSenderCompId) {
        long nonce = ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999L);
        return properties.getClOrdIdPrefix() + shadowSenderCompId + "-" + Instant.now().toEpochMilli() + "-" + nonce;
    }

    private String getSafeClOrdId(NewOrderSingle order) {
        try {
            return order.getClOrdID().getValue();
        } catch (FieldNotFound e) {
            return "<missing>";
        }
    }

    public Set<String> getOnlineSessions() {
        return sessionsBySenderCompId.keySet();
    }
}
