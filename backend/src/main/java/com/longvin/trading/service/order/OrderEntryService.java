package com.longvin.trading.service.order;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.fix.FixApplicationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.math.BigDecimal;

/**
 * Service for placing DAS FIX New Order Single messages via the existing
 * QuickFIX/J order-entry initiator sessions.
 */
@Service
public class OrderEntryService {

    private static final Logger log = LoggerFactory.getLogger(OrderEntryService.class);

    private final FixClientProperties properties;
    private final FixApplicationUtils utils;

    public OrderEntryService(FixClientProperties properties,
                             FixApplicationUtils utils) {
        this.properties = properties;
        this.utils = utils;
    }

    public NewOrderSingleResponse submitNewOrder(NewOrderSingleRequest request) {
        // Minimal manual validation
        if (request == null) {
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.VALIDATION_FAILED,
                    null, null, "request must not be null");
        }
        if (request.getSenderCompId() == null || request.getSenderCompId().isBlank()) {
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.VALIDATION_FAILED,
                    null, request.getClOrdId(), "senderCompId must not be blank");
        }
        if (request.getClOrdId() == null || request.getClOrdId().isBlank()) {
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.VALIDATION_FAILED,
                    request.getSenderCompId(), null, "clOrdId must not be blank");
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.VALIDATION_FAILED,
                    request.getSenderCompId(), request.getClOrdId(), "symbol must not be blank");
        }
        if (request.getSide() == null || request.getOrdType() == null || request.getTimeInForce() == null) {
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.VALIDATION_FAILED,
                    request.getSenderCompId(), request.getClOrdId(), "side, ordType and timeInForce are required");
        }
        if (request.getQuantity() == null || request.getQuantity().doubleValue() <= 0) {
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.VALIDATION_FAILED,
                    request.getSenderCompId(), request.getClOrdId(), "quantity must be > 0");
        }

        String senderCompId = request.getSenderCompId();
        quickfix.SessionID sessionID = utils.getSessionIdForSenderCompId(senderCompId).orElse(null);
        if (sessionID == null) {
            String msg = "No logged-on FIX session for senderCompId=" + senderCompId;
            log.warn(msg);
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.SESSION_OFFLINE,
                    senderCompId, request.getClOrdId(), msg);
        }

        quickfix.Session session = quickfix.Session.lookupSession(sessionID);
        if (session == null || !session.isLoggedOn()) {
            String msg = "FIX session not logged on for " + sessionID;
            log.warn(msg);
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.SESSION_OFFLINE,
                    senderCompId, request.getClOrdId(), msg);
        }

        try {
            NewOrderSingle newOrder = buildNewOrderSingle(request);
            quickfix.Session.sendToTarget(newOrder, sessionID);
            log.info("Sent NewOrderSingle clOrdId={} symbol={} side={} qty={} price={} via {}",
                    request.getClOrdId(), request.getSymbol(), request.getSide(),
                    request.getQuantity(), request.getPrice(), sessionID);
            return NewOrderSingleResponse.success(senderCompId, request.getClOrdId(), sessionID.toString());
        } catch (SessionNotFound e) {
            String msg = "Session not found when sending order: " + e.getMessage();
            log.error(msg, e);
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.SESSION_OFFLINE,
                    senderCompId, request.getClOrdId(), msg);
        } catch (Exception e) {
            String msg = "Failed to build or send NewOrderSingle: " + e.getMessage();
            log.error(msg, e);
            return NewOrderSingleResponse.failure(NewOrderSingleResponse.Status.SEND_FAILED,
                    senderCompId, request.getClOrdId(), msg);
        }
    }

    private NewOrderSingle buildNewOrderSingle(NewOrderSingleRequest req) {
        // Map enums to FIX values
        char sideVal = mapSide(req.getSide());
        char ordTypeVal = mapOrdType(req.getOrdType());
        char tifVal = mapTimeInForce(req.getTimeInForce());

        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(truncateClOrdId(req.getClOrdId())),
                new HandlInst('1'), // AUTOMATED_EXECUTION_ORDER_PRIVATE
                new Symbol(req.getSymbol()),
                new Side(sideVal),
                new TransactTime(),
                new OrdType(ordTypeVal)
        );

        BigDecimal qty = req.getQuantity();
        order.set(new OrderQty(qty.doubleValue()));

        if (req.getPrice() != null && (req.getOrdType() == NewOrderSingleRequest.OrdType.LIMIT
                || req.getOrdType() == NewOrderSingleRequest.OrdType.STOP
                || req.getOrdType() == NewOrderSingleRequest.OrdType.STOP_LIMIT)) {
            order.set(new Price(req.getPrice().doubleValue()));
        }

        order.set(new TimeInForce(tifVal));

        if (req.getAccount() != null && !req.getAccount().isBlank()) {
            order.set(new Account(req.getAccount()));
        } else {
            // Let existing logic override account if configured
            utils.overrideAccountIfNeeded(order, req.getSenderCompId());
        }

        return order;
    }

    private String truncateClOrdId(String clOrdId) {
        if (clOrdId == null) return null;
        if (clOrdId.length() <= 19) {
            return clOrdId;
        }
        // DAS spec: ClOrdID length 19; truncate from the left (keep most recent/rightmost chars)
        return clOrdId.substring(clOrdId.length() - 19);
    }

    private char mapSide(NewOrderSingleRequest.Side side) {
        switch (side) {
            case BUY: return Side.BUY;
            case SELL: return Side.SELL;
            case SELL_SHORT: return Side.SELL_SHORT;
            case SELL_SHORT_EXEMPT: return Side.SELL_SHORT_EXEMPT;
            default: throw new IllegalArgumentException("Unsupported side: " + side);
        }
    }

    private char mapOrdType(NewOrderSingleRequest.OrdType ordType) {
        switch (ordType) {
            case MARKET: return OrdType.MARKET;
            case LIMIT: return OrdType.LIMIT;
            case STOP: return '3'; // STOP
            case STOP_LIMIT: return OrdType.STOP_LIMIT;
            default: throw new IllegalArgumentException("Unsupported ordType: " + ordType);
        }
    }

    private char mapTimeInForce(NewOrderSingleRequest.TimeInForce tif) {
        switch (tif) {
            case DAY: return TimeInForce.DAY;
            case IOC: return TimeInForce.IMMEDIATE_OR_CANCEL;
            case GTC: return TimeInForce.GOOD_TILL_CANCEL;
            case GTX: return TimeInForce.GOOD_TILL_CROSSING;
            case DAY_PLUS: return TimeInForce.GOOD_TILL_DATE; // DAS maps tag 59=5 to Day+
            default: throw new IllegalArgumentException("Unsupported TIF: " + tif);
        }
    }
}
