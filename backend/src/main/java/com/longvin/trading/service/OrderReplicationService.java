package com.longvin.trading.service;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.fix.FixApplicationUtils;
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
    private final FixApplicationUtils utils;
    private final Executor executor;

    public OrderReplicationService(FixClientProperties properties,
                                   FixApplicationUtils utils,
                                   @Qualifier("orderMirroringExecutor") Executor executor) {
        this.properties = properties;
        this.utils = utils;
        this.executor = executor;
    }

    public void replicateOrderToShadowsAsync(NewOrderSingle order, String senderCompId, Map<String, SessionID> sessionsBySenderCompId) {
        Set<String> shadowSessions = properties.getShadowSessions();
        for (String shadowSenderCompId : shadowSessions) {
            executor.execute(() -> {
                try {
                    replicateOrder(order, senderCompId, shadowSenderCompId, sessionsBySenderCompId);
                } catch (FieldNotFound e) {
                    log.error("FieldNotFound while mirroring order {} to {}: {}", utils.getSafeClOrdId(order), shadowSenderCompId, e.getMessage(), e);
                }
            });
        }
    }

    private void replicateOrder(NewOrderSingle order, String senderCompId, String shadowSenderCompId, Map<String, SessionID> sessionsBySenderCompId) throws FieldNotFound {
        SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
        if (shadowSession == null) {
            log.warn("Shadow session {} is not logged on; unable to mirror order {}", shadowSenderCompId, utils.getSafeClOrdId(order));
            return;
        }
        try {
            NewOrderSingle mirroredOrder = utils.cloneOrder(order);
            utils.overrideAccountIfNeeded(mirroredOrder, shadowSenderCompId);
            mirroredOrder.set(new quickfix.field.ClOrdID(utils.generateMirrorClOrdId(shadowSenderCompId, order.getClOrdID().getValue(), "N")));
            Session.sendToTarget(mirroredOrder, shadowSession);
            log.info("Mirrored live order {} from {} to {}", utils.getSafeClOrdId(order), senderCompId, shadowSenderCompId);
        } catch (CloneNotSupportedException | SessionNotFound ex) {
            log.error("Failed to mirror order {} to {}, reason: {}", utils.getSafeClOrdId(order), shadowSenderCompId, ex.getMessage(), ex);
        }
    }
}
