package com.longvin.trading.fix;

import com.longvin.trading.config.FixClientProperties;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.Account;
import quickfix.fix42.NewOrderSingle;

import java.util.Map;
import java.util.Optional;

/**
 * Utility methods shared between FIX applications for order handling.
 */
@Component
public class FixApplicationUtils {

    private final FixClientProperties properties;
    private final FixSessionRegistry sessionRegistry;

    public FixApplicationUtils(FixClientProperties properties, FixSessionRegistry sessionRegistry) {
        this.properties = properties;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Get the SessionID for a given senderCompId.
     * @param senderCompId the FIX SenderCompID
     * @return Optional containing the SessionID if found and logged on, empty otherwise
     */
    public Optional<quickfix.SessionID> getSessionIdForSenderCompId(String senderCompId) {
        return sessionRegistry.findLoggedOnInitiatorByAlias(senderCompId);
    }

    /**
     * Clone a NewOrderSingle message.
     */
    public NewOrderSingle cloneOrder(NewOrderSingle order) throws CloneNotSupportedException {
        return (NewOrderSingle) order.clone();
    }

    /**
     * Override account field if needed based on shadow account configuration.
     */
    public void overrideAccountIfNeeded(Message order, String shadowSenderCompId) {
        Map<String, String> overrides = properties.getShadowAccounts();
        if (overrides.isEmpty()) {
            return;
        }
        Optional.ofNullable(overrides.get(shadowSenderCompId))
            .ifPresent(account -> order.setField(new Account(account)));
    }

    /**
     * Generate a mirror ClOrdID for shadow orders.
     */
    public String generateMirrorClOrdId(String shadowSenderCompId, String source, String action) {
        String base = properties.getClOrdIdPrefix() + action + "-" + shadowSenderCompId + "-" + source;
        if (base.length() > 19) {
            return base.substring(base.length() - 19);
        }
        return base;
    }

    /**
     * Safely get ClOrdID from a NewOrderSingle, returning "<missing>" if not found.
     */
    public String getSafeClOrdId(NewOrderSingle order) {
        try {
            return order.getClOrdID().getValue();
        } catch (FieldNotFound e) {
            return "<missing>";
        }
    }
}


