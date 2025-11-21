package com.longvin.trading.service;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.fix.OrderReplicationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import quickfix.*;
import quickfix.fix42.NewOrderSingle;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@EnableAsync
public class OrderReplicationService {
    private static final Logger log = LoggerFactory.getLogger(OrderReplicationService.class);

    private final FixClientProperties properties;
    private final Executor executor;

    public OrderReplicationService(FixClientProperties properties,
                                   @Qualifier("orderMirroringExecutor") Executor executor) {
        this.properties = properties;
        this.executor = executor;
    }

    public void replicateOrderToShadowsAsync(NewOrderSingle order, String senderCompId, OrderReplicationCoordinator app, Map<String, SessionID> sessionsBySenderCompId) {
        Set<String> shadowSessions = properties.getShadowSessions();
        for (String shadowSenderCompId : shadowSessions) {
            executor.execute(() -> {
                try {
                    replicateOrder(order, senderCompId, shadowSenderCompId, app, sessionsBySenderCompId);
                } catch (FieldNotFound e) {
                    log.error("FieldNotFound while mirroring order {} to {}: {}", app.getSafeClOrdId(order), shadowSenderCompId, e.getMessage(), e);
                }
            });
        }
    }

    private void replicateOrder(NewOrderSingle order, String senderCompId, String shadowSenderCompId, OrderReplicationCoordinator app, Map<String, SessionID> sessionsBySenderCompId) throws FieldNotFound {
        SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
        if (shadowSession == null) {
            log.warn("Shadow session {} is not logged on; unable to mirror order {}", shadowSenderCompId, app.getSafeClOrdId(order));
            return;
        }
        try {
            NewOrderSingle mirroredOrder = app.cloneOrder(order);
            app.overrideAccountIfNeeded(mirroredOrder, shadowSenderCompId);
            mirroredOrder.set(new quickfix.field.ClOrdID(app.generateMirrorClOrdId(shadowSenderCompId, order.getClOrdID().getValue(), "N")));
            Session.sendToTarget(mirroredOrder, shadowSession);
            log.info("Mirrored live order {} from {} to {}", app.getSafeClOrdId(order), senderCompId, shadowSenderCompId);
        } catch (CloneNotSupportedException | SessionNotFound ex) {
            log.error("Failed to mirror order {} to {}, reason: {}", app.getSafeClOrdId(order), shadowSenderCompId, ex.getMessage(), ex);
        }
    }
}
