package com.longvin.trading.fix;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.OrdType;
import quickfix.field.OrigClOrdID;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.StopPx;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.MessageCracker;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

@Component
public class MirrorTradingApplication extends MessageCracker implements Application {

    private static final Logger log = LoggerFactory.getLogger(MirrorTradingApplication.class);

    private final FixClientProperties properties;
    private final Map<String, SessionID> sessionsBySenderCompId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PrimaryOrderState> primaryOrders = new ConcurrentHashMap<>();
    private final Set<String> processedExecIds = ConcurrentHashMap.newKeySet();

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
        mirrorExplicitOrder(order, sessionID);
    }

    @Override
    public void onMessage(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        handleExecutionReport(report, sessionID);
    }

    private void mirrorExplicitOrder(NewOrderSingle order, SessionID sessionID) throws FieldNotFound {
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
                mirroredOrder.set(new ClOrdID(generateMirrorClOrdId(shadowSenderCompId, order.getClOrdID().getValue(), "N")));
                Session.sendToTarget(mirroredOrder, shadowSession);
                log.info("Mirrored live order {} from {} to {}", getSafeClOrdId(order), senderCompId, shadowSenderCompId);
            } catch (CloneNotSupportedException | SessionNotFound ex) {
                log.error("Failed to mirror order {} to {}, reason: {}", getSafeClOrdId(order), shadowSenderCompId,
                        ex.getMessage(), ex);
            }
        }
    }

    private void handleExecutionReport(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        if (!sessionID.getSenderCompID().equalsIgnoreCase(properties.getPrimarySession())) {
            log.trace("Ignoring execution report from non drop-copy session {}", sessionID);
            return;
        }
        String execId = report.getExecID().getValue();
        if (!processedExecIds.add(execId)) {
            log.trace("Skipping duplicate execution {}", execId);
            return;
        }

        String account = report.isSetField(Account.FIELD) ? report.getString(Account.FIELD) : null;
        if (account != null) {
            if (properties.getShadowAccountValues().contains(account)) {
                log.trace("Ignoring execution {} for shadow account {}", execId, account);
                return;
            }
            Optional<String> primaryAccount = properties.getPrimaryAccount();
            if (primaryAccount.isPresent() && !primaryAccount.get().equalsIgnoreCase(account)) {
                log.trace("Ignoring execution {} for non-primary account {}", execId, account);
                return;
            }
        } else if (properties.getPrimaryAccount().isPresent()) {
            log.trace("Ignoring execution {} lacking account tag", execId);
            return;
        }

        String orderId = report.getString(OrderID.FIELD);
        if (orderId == null || orderId.isBlank() || "0".equals(orderId)) {
            log.debug("Skipping execution {} due to missing DAS order id", execId);
            return;
        }

        char execType = report.getExecType().getValue();
        if (execType == ExecType.NEW) {
            handleDropCopyNew(report, account, orderId);
        } else if (execType == ExecType.REPLACED) {
            handleDropCopyReplace(report, orderId);
        } else if (execType == ExecType.CANCELED) {
            handleDropCopyCancel(report, orderId);
        } else {
            log.trace("Observed execution {} type {} for order {}", execId, execType, orderId);
        }
    }

    private void handleDropCopyNew(ExecutionReport report, String account, String orderId) throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.computeIfAbsent(orderId, id -> new PrimaryOrderState(id));
        state.updateFrom(report, account);
        if (!state.markMirrored()) {
            return;
        }
        replicateNewOrderToShadows(report, state);
    }

    private void handleDropCopyReplace(ExecutionReport report, String orderId) throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.get(orderId);
        if (state == null) {
            log.warn("Received replace exec report for unknown order {}", orderId);
            return;
        }
        state.updateFrom(report, state.account);
        replicateReplaceToShadows(report, state);
    }

    private void handleDropCopyCancel(ExecutionReport report, String orderId) throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.get(orderId);
        if (state == null) {
            log.warn("Received cancel exec report for unknown order {}", orderId);
            return;
        }
        replicateCancelToShadows(report, state);
        primaryOrders.remove(orderId);
    }

    private void replicateNewOrderToShadows(ExecutionReport report, PrimaryOrderState state) {
        if (properties.getShadowSessions().isEmpty()) {
            log.debug("No shadow sessions configured; skipping drop-copy mirror for order {}", state.orderId);
            return;
        }

        for (String shadowSenderCompId : properties.getShadowSessions()) {
            SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror drop-copy order {}", shadowSenderCompId,
                        state.orderId);
                continue;
            }

            ShadowOrderState shadowState = state.shadows.computeIfAbsent(shadowSenderCompId, id -> new ShadowOrderState());
            String clOrdId = generateMirrorClOrdId(shadowSenderCompId, state.orderId, "N");
            NewOrderSingle mirroredOrder = buildMirroredNewOrder(report, state, clOrdId, shadowSenderCompId);
            if (mirroredOrder == null) {
                continue;
            }

            try {
                Session.sendToTarget(mirroredOrder, shadowSession);
                shadowState.currentClOrdId = clOrdId;
                log.info("Drop-copy mirrored order {} -> {} (ClOrdID={})", state.orderId, shadowSenderCompId, clOrdId);
            } catch (SessionNotFound ex) {
                log.error("Failed to send mirrored order {} to {}, reason: {}", state.orderId, shadowSenderCompId,
                        ex.getMessage(), ex);
            }
        }
    }

    private void replicateReplaceToShadows(ExecutionReport report, PrimaryOrderState state) {
        for (Map.Entry<String, ShadowOrderState> entry : state.shadows.entrySet()) {
            String shadowSenderCompId = entry.getKey();
            ShadowOrderState shadowState = entry.getValue();
            SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror replace for order {}", shadowSenderCompId,
                        state.orderId);
                continue;
            }
            if (shadowState.currentClOrdId == null) {
                log.debug("No known shadow order for {} on {}; skipping replace", state.orderId, shadowSenderCompId);
                continue;
            }

            String clOrdId = generateMirrorClOrdId(shadowSenderCompId, state.orderId, "R");
            OrderCancelReplaceRequest replace = buildMirroredReplace(report, state, clOrdId, shadowState.currentClOrdId,
                    shadowSenderCompId);
            if (replace == null) {
                continue;
            }

            try {
                Session.sendToTarget(replace, shadowSession);
                shadowState.currentClOrdId = clOrdId;
                log.info("Drop-copy mirrored replace for order {} on {} (new ClOrdID={})", state.orderId,
                        shadowSenderCompId, clOrdId);
            } catch (SessionNotFound ex) {
                log.error("Failed to send mirrored replace for order {} to {}, reason: {}", state.orderId,
                        shadowSenderCompId, ex.getMessage(), ex);
            }
        }
    }

    private void replicateCancelToShadows(ExecutionReport report, PrimaryOrderState state) {
        for (Map.Entry<String, ShadowOrderState> entry : state.shadows.entrySet()) {
            String shadowSenderCompId = entry.getKey();
            ShadowOrderState shadowState = entry.getValue();
            SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror cancel for order {}", shadowSenderCompId,
                        state.orderId);
                continue;
            }
            if (shadowState.currentClOrdId == null) {
                log.debug("No known shadow order for {} on {}; skipping cancel", state.orderId, shadowSenderCompId);
                continue;
            }

            String clOrdId = generateMirrorClOrdId(shadowSenderCompId, state.orderId, "C");
            OrderCancelRequest cancel = buildMirroredCancel(report, state, clOrdId, shadowState.currentClOrdId,
                    shadowSenderCompId);
            if (cancel == null) {
                continue;
            }
            try {
                Session.sendToTarget(cancel, shadowSession);
                log.info("Drop-copy mirrored cancel for order {} on {}", state.orderId, shadowSenderCompId);
            } catch (SessionNotFound ex) {
                log.error("Failed to send mirrored cancel for order {} to {}, reason: {}", state.orderId,
                        shadowSenderCompId, ex.getMessage(), ex);
            }
        }
    }

    private NewOrderSingle buildMirroredNewOrder(ExecutionReport report, PrimaryOrderState state, String clOrdId,
                                                 String shadowSenderCompId) {
        if (state.symbol == null || state.side == null || state.orderQty == null) {
            log.warn("Insufficient data to mirror order {}: symbol={}, side={}, qty={}", state.orderId, state.symbol,
                    state.side, state.orderQty);
            return null;
        }

        char ordType = resolveOrdType(state);
        LocalDateTime transactTime = Optional.ofNullable(state.transactTime)
                .orElse(LocalDateTime.now(ZoneOffset.UTC));

        NewOrderSingle mirrored = new NewOrderSingle();
        mirrored.set(new ClOrdID(clOrdId));
        mirrored.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        mirrored.set(new Symbol(state.symbol));
        mirrored.set(new Side(state.side));
        mirrored.set(new TransactTime(transactTime));
        mirrored.set(new OrdType(ordType));
        mirrored.set(new OrderQty(state.orderQty.doubleValue()));

        setPriceFields(ordType, state, mirrored);
        setTimeInForce(state, mirrored);
        overrideAccountIfNeeded(mirrored, shadowSenderCompId);

        return mirrored;
    }

    private OrderCancelReplaceRequest buildMirroredReplace(ExecutionReport report, PrimaryOrderState state,
                                                           String clOrdId, String origClOrdId, String shadowSenderCompId) {
        char ordType = resolveOrdType(state);
        LocalDateTime transactTime = Optional.ofNullable(state.transactTime)
                .orElse(LocalDateTime.now(ZoneOffset.UTC));

        OrderCancelReplaceRequest replace = new OrderCancelReplaceRequest();
        replace.set(new ClOrdID(clOrdId));
        replace.set(new OrigClOrdID(origClOrdId));
        replace.set(new Symbol(state.symbol));
        replace.set(new Side(state.side));
        replace.set(new TransactTime(transactTime));
        replace.set(new OrdType(ordType));
        if (state.orderQty != null) {
            replace.set(new OrderQty(state.orderQty.doubleValue()));
        }

        setPriceFields(ordType, state, replace);
        setTimeInForce(state, replace);
        overrideAccountIfNeeded(replace, shadowSenderCompId);

        return replace;
    }

    private OrderCancelRequest buildMirroredCancel(ExecutionReport report, PrimaryOrderState state, String clOrdId,
                                                   String origClOrdId, String shadowSenderCompId) {
        LocalDateTime transactTime = Optional.ofNullable(state.transactTime)
                .orElse(LocalDateTime.now(ZoneOffset.UTC));

        OrderCancelRequest cancel = new OrderCancelRequest();
        cancel.set(new ClOrdID(clOrdId));
        cancel.set(new OrigClOrdID(origClOrdId));
        cancel.set(new Symbol(state.symbol));
        cancel.set(new Side(state.side));
        cancel.set(new TransactTime(transactTime));
        if (state.orderQty != null) {
            cancel.set(new OrderQty(state.orderQty.doubleValue()));
        }
        overrideAccountIfNeeded(cancel, shadowSenderCompId);
        return cancel;
    }

    private void setPriceFields(char ordType, PrimaryOrderState state, Message message) {
        if (state.price != null && (ordType == OrdType.LIMIT || ordType == OrdType.STOP_LIMIT
                || ordType == OrdType.PEGGED || ordType == OrdType.LIMIT_ON_CLOSE)) {
            message.setField(new Price(state.price.doubleValue()));
        }
        if (state.stopPrice != null && (ordType == OrdType.STOP_STOP_LOSS || ordType == OrdType.STOP_LIMIT)) {
            message.setField(new StopPx(state.stopPrice.doubleValue()));
        }
    }

    private void setTimeInForce(PrimaryOrderState state, Message message) {
        char tif = state.timeInForce != null ? state.timeInForce : TimeInForce.DAY;
        message.setField(new TimeInForce(tif));
    }

    private char resolveOrdType(PrimaryOrderState state) {
        if (state.ordType != null) {
            return state.ordType;
        }
        if (state.price != null) {
            return OrdType.LIMIT;
        }
        if (state.stopPrice != null) {
            return OrdType.STOP_STOP_LOSS;
        }
        return OrdType.MARKET;
    }

    private NewOrderSingle cloneOrder(NewOrderSingle order) throws CloneNotSupportedException {
        return (NewOrderSingle) order.clone();
    }

    private void overrideAccountIfNeeded(Message order, String shadowSenderCompId) {
        Map<String, String> overrides = properties.getShadowAccounts();
        if (overrides.isEmpty()) {
            return;
        }
        Optional.ofNullable(overrides.get(shadowSenderCompId)).ifPresent(account -> order.setField(new Account(account)));
    }

    private String generateMirrorClOrdId(String shadowSenderCompId, String source, String action) {
        String base = properties.getClOrdIdPrefix() + action + "-" + shadowSenderCompId + "-" + source;
        if (base.length() > 19) {
            return base.substring(base.length() - 19);
        }
        return base;
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

    private static final class PrimaryOrderState {
        private final String orderId;
        private volatile boolean mirrored;
        private String clOrdId;
        private String account;
        private String symbol;
        private Character side;
        private Character ordType;
        private Character timeInForce;
        private BigDecimal orderQty;
        private BigDecimal price;
        private BigDecimal stopPrice;
        private LocalDateTime transactTime;
        private final ConcurrentMap<String, ShadowOrderState> shadows = new ConcurrentHashMap<>();

        private PrimaryOrderState(String orderId) {
            this.orderId = orderId;
        }

        private void updateFrom(ExecutionReport report, String account) throws FieldNotFound {
            this.clOrdId = report.getClOrdID().getValue();
            this.account = account;
            if (report.isSetField(Symbol.FIELD)) {
                this.symbol = report.getString(Symbol.FIELD);
            }
            if (report.isSetField(Side.FIELD)) {
                this.side = report.getChar(Side.FIELD);
            }
            if (report.isSetField(OrdType.FIELD)) {
                this.ordType = report.getChar(OrdType.FIELD);
            }
            if (report.isSetField(TimeInForce.FIELD)) {
                this.timeInForce = report.getChar(TimeInForce.FIELD);
            }
            if (report.isSetField(OrderQty.FIELD)) {
                this.orderQty = report.getDecimal(OrderQty.FIELD);
            }
            if (report.isSetField(Price.FIELD)) {
                this.price = report.getDecimal(Price.FIELD);
            }
            if (report.isSetField(StopPx.FIELD)) {
                this.stopPrice = report.getDecimal(StopPx.FIELD);
            }
            if (report.isSetField(TransactTime.FIELD)) {
                this.transactTime = report.getTransactTime().getValue();
            }
        }

        private boolean markMirrored() {
            if (mirrored) {
                return false;
            }
            mirrored = true;
            return true;
        }
    }

    private static final class ShadowOrderState {
        private volatile String currentClOrdId;
    }
}
