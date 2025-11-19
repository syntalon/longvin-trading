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

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.service.OrderReplicationService;
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
public class OrderReplicationCoordinator extends MessageCracker implements Application {

    private static final Logger log = LoggerFactory.getLogger(OrderReplicationCoordinator.class);

    private final FixClientProperties properties;
    private final Map<String, SessionID> sessionsBySenderCompId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PrimaryOrderState> primaryOrders = new ConcurrentHashMap<>();
    private final Set<String> processedExecIds = ConcurrentHashMap.newKeySet();
    private final OrderReplicationService orderReplicationService;

    public OrderReplicationCoordinator(FixClientProperties properties, OrderReplicationService orderReplicationService) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.orderReplicationService = Objects.requireNonNull(orderReplicationService, "orderMirroringService must not be null");
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
        try {
            Session session = Session.lookupSession(sessionID);
            if (session != null) {
                log.warn("Logged out from FIX session {} - Session state: isLoggedOn={}, isEnabled={}", 
                    sessionID, session.isLoggedOn(), session.isEnabled());
            } else {
                log.warn("Logged out from FIX session {} - Session object not found", sessionID);
            }
        } catch (Exception e) {
            log.warn("Logged out from FIX session {} - Error getting session details: {}", sessionID, e.getMessage());
        }
        log.warn("Logged out from FIX session {} - this may indicate a connection issue or server-side timeout", sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("A".equals(msgType)) {
                // Add ResetSeqNumFlag to reset sequence numbers on logon
                // This helps when there's a sequence number mismatch with the server
                if (!message.isSetField(quickfix.field.ResetSeqNumFlag.FIELD)) {
                    message.setField(new quickfix.field.ResetSeqNumFlag(true));
                }
                // Extract our heartbeat interval to log it
                int clientHeartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    clientHeartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                log.info("Sending Logon request to {}: Client HeartBtInt={} seconds, message={}", 
                    sessionID, clientHeartBtInt, message);
            } else if ("5".equals(msgType)) {
                log.info("Sending Logout to {}: {}", sessionID, message);
            } else if ("0".equals(msgType)) {
                // Heartbeat - log to verify they're being sent
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.info("Sending Heartbeat to {}: seqNum={}", sessionID, seqNum);
            } else if ("1".equals(msgType)) {
                log.debug("Sending TestRequest to {}", sessionID);
            }
        } catch (Exception e) {
            log.debug("Error processing outgoing admin message to {}: {}", sessionID, e.getMessage());
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(quickfix.field.MsgType.FIELD);
            if ("A".equals(msgType)) {
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                // Extract server's heartbeat interval (field 108)
                int serverHeartBtInt = -1;
                if (message.isSetField(quickfix.field.HeartBtInt.FIELD)) {
                    serverHeartBtInt = message.getInt(quickfix.field.HeartBtInt.FIELD);
                }
                log.info("Received Logon response from {}: seqNum={}, Server HeartBtInt={} seconds, message={}", 
                    sessionID, seqNum, serverHeartBtInt, message);
            } else if ("5".equals(msgType)) {
                String text = message.isSetField(quickfix.field.Text.FIELD) 
                    ? message.getString(quickfix.field.Text.FIELD) 
                    : "No reason provided";
                int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                log.warn("Received Logout from {}: seqNum={}, reason={}", sessionID, seqNum, text);
            } else if ("0".equals(msgType)) {
                // Heartbeat message - log to verify connection is alive
                try {
                    int seqNum = message.getHeader().getInt(quickfix.field.MsgSeqNum.FIELD);
                    // Check if this is a response to a TestRequest
                    boolean isTestResponse = message.isSetField(quickfix.field.TestReqID.FIELD);
                    if (isTestResponse) {
                        String testReqId = message.getString(quickfix.field.TestReqID.FIELD);
                        log.info("Received Heartbeat (TestRequest response) from {}: seqNum={}, TestReqID={}", sessionID, seqNum, testReqId);
                    } else {
                        log.info("Received Heartbeat from {}: seqNum={}", sessionID, seqNum);
                    }
                } catch (Exception e) {
                    log.warn("Error processing heartbeat from {}: {}", sessionID, e.getMessage(), e);
                }
            } else if ("1".equals(msgType)) {
                log.debug("Received TestRequest from {}", sessionID);
            } else {
                log.debug("Received admin message {} from {}: {}", msgType, sessionID, message);
            }
        } catch (Exception e) {
            log.debug("Error processing admin message from {}: {}", sessionID, e.getMessage());
        }
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
    public void onMessage(NewOrderSingle order, SessionID sessionID) {
        mirrorExplicitOrder(order, sessionID);
    }

    @Override
    public void onMessage(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        handleExecutionReport(report, sessionID);
    }

    private void mirrorExplicitOrder(NewOrderSingle order, SessionID sessionID) {
        String senderCompId = sessionID.getSenderCompID();
        if (!senderCompId.equalsIgnoreCase(properties.getPrimarySession())) {
            return;
        }
        if (properties.getShadowSessions().isEmpty()) {
            log.debug("No shadow sessions configured; skipping mirror for {}", order);
            return;
        }
        // Delegate to async mirroring service
        orderReplicationService.replicateOrderToShadowsAsync(order, senderCompId, this, sessionsBySenderCompId);
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
            handleDropCopyCancel(orderId);
        } else {
            log.trace("Observed execution {} type {} for order {}", execId, execType, orderId);
        }
    }

    private void handleDropCopyNew(ExecutionReport report, String account, String orderId) throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.computeIfAbsent(orderId, PrimaryOrderState::new);
        state.updateFrom(report, account);
        if (!state.markMirrored()) {
            return;
        }
        replicateNewOrderToShadows(state);
    }

    private void handleDropCopyReplace(ExecutionReport report, String orderId) throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.get(orderId);
        if (state == null) {
            log.warn("Received replace exec report for unknown order {}", orderId);
            return;
        }
        state.updateFrom(report, state.account);
        replicateReplaceToShadows(state);
    }

    private void handleDropCopyCancel(String orderId) {
        PrimaryOrderState state = primaryOrders.get(orderId);
        if (state == null) {
            log.warn("Received cancel exec report for unknown order {}", orderId);
            return;
        }
        replicateCancelToShadows(state);
        primaryOrders.remove(orderId);
    }

    // --- Drop-copy mirroring helpers ---
    private void replicateNewOrderToShadows(final PrimaryOrderState state) {
        if (properties.getShadowSessions().isEmpty()) {
            log.debug("No shadow sessions configured; skipping drop-copy mirror for order {}", state.orderId);
            return;
        }
        for (final String shadowSenderCompId : properties.getShadowSessions()) {
            final SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror drop-copy order {}", shadowSenderCompId, state.orderId);
                continue;
            }
            final ShadowOrderState shadowState = state.shadows.computeIfAbsent(shadowSenderCompId, id -> new ShadowOrderState());
            final String clOrdId = generateMirrorClOrdId(shadowSenderCompId, state.orderId, "N");
            final NewOrderSingle mirroredOrder = buildMirroredNewOrder(state, clOrdId, shadowSenderCompId);
            if (mirroredOrder == null) {
                continue;
            }
            try {
                Session.sendToTarget(mirroredOrder, shadowSession);
                shadowState.currentClOrdId = clOrdId;
                log.info("Drop-copy mirrored order {} -> {} (ClOrdID={})", state.orderId, shadowSenderCompId, clOrdId);
            } catch (SessionNotFound ex) {
                log.error("Failed to send mirrored order {} to {}, reason: {}", state.orderId, shadowSenderCompId, ex.getMessage(), ex);
            }
        }
    }

    private void replicateReplaceToShadows(final PrimaryOrderState state) {
        state.shadows.forEach((shadowSenderCompId, shadowState) -> {
            final SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror replace for order {}", shadowSenderCompId, state.orderId);
                return;
            }
            if (shadowState.currentClOrdId == null) {
                log.debug("No known shadow order for {} on {}; skipping replace", state.orderId, shadowSenderCompId);
                return;
            }
            final String clOrdId = generateMirrorClOrdId(shadowSenderCompId, state.orderId, "R");
            final OrderCancelReplaceRequest replace = buildMirroredReplace(state, clOrdId, shadowState.currentClOrdId, shadowSenderCompId);
            try {
                Session.sendToTarget(replace, shadowSession);
                shadowState.currentClOrdId = clOrdId;
                log.info("Drop-copy mirrored replace for order {} on {} (new ClOrdID={})", state.orderId, shadowSenderCompId, clOrdId);
            } catch (SessionNotFound ex) {
                log.error("Failed to send mirrored replace for order {} to {}, reason: {}", state.orderId, shadowSenderCompId, ex.getMessage(), ex);
            }
        });
    }

    private void replicateCancelToShadows(final PrimaryOrderState state) {
        state.shadows.forEach((shadowSenderCompId, shadowState) -> {
            final SessionID shadowSession = sessionsBySenderCompId.get(shadowSenderCompId);
            if (shadowSession == null) {
                log.warn("Shadow session {} is not logged on; unable to mirror cancel for order {}", shadowSenderCompId, state.orderId);
                return;
            }
            if (shadowState.currentClOrdId == null) {
                log.debug("No known shadow order for {} on {}; skipping cancel", state.orderId, shadowSenderCompId);
                return;
            }
            final String clOrdId = generateMirrorClOrdId(shadowSenderCompId, state.orderId, "C");
            final OrderCancelRequest cancel = buildMirroredCancel(state, clOrdId, shadowState.currentClOrdId, shadowSenderCompId);
            try {
                Session.sendToTarget(cancel, shadowSession);
                log.info("Drop-copy mirrored cancel for order {} on {}", state.orderId, shadowSenderCompId);
            } catch (SessionNotFound ex) {
                log.error("Failed to send mirrored cancel for order {} to {}, reason: {}", state.orderId, shadowSenderCompId, ex.getMessage(), ex);
            }
        });
    }

    private NewOrderSingle buildMirroredNewOrder(final PrimaryOrderState state, final String clOrdId, final String shadowSenderCompId) {
        if (state.symbol == null || state.side == null || state.orderQty == null) {
            log.warn("Insufficient data to mirror order {}: symbol={}, side={}, qty={}", state.orderId, state.symbol, state.side, state.orderQty);
            return null;
        }
        final char ordType = resolveOrdType(state);
        final LocalDateTime transactTime = Optional.ofNullable(state.transactTime).orElse(LocalDateTime.now(ZoneOffset.UTC));
        final NewOrderSingle mirrored = new NewOrderSingle();
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

    private OrderCancelReplaceRequest buildMirroredReplace(final PrimaryOrderState state, final String clOrdId, final String origClOrdId, final String shadowSenderCompId) {
        final char ordType = resolveOrdType(state);
        final LocalDateTime transactTime = Optional.ofNullable(state.transactTime).orElse(LocalDateTime.now(ZoneOffset.UTC));
        final OrderCancelReplaceRequest replace = new OrderCancelReplaceRequest();
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

    private OrderCancelRequest buildMirroredCancel(final PrimaryOrderState state, final String clOrdId, final String origClOrdId, final String shadowSenderCompId) {
        final LocalDateTime transactTime = Optional.ofNullable(state.transactTime).orElse(LocalDateTime.now(ZoneOffset.UTC));
        final OrderCancelRequest cancel = new OrderCancelRequest();
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
    // --- End of drop-copy mirroring helpers ---

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

    public NewOrderSingle cloneOrder(NewOrderSingle order) throws CloneNotSupportedException {
        return (NewOrderSingle) order.clone();
    }

    public void overrideAccountIfNeeded(Message order, String shadowSenderCompId) {
        Map<String, String> overrides = properties.getShadowAccounts();
        if (overrides.isEmpty()) {
            return;
        }
        Optional.ofNullable(overrides.get(shadowSenderCompId)).ifPresent(account -> order.setField(new Account(account)));
    }

    public String generateMirrorClOrdId(String shadowSenderCompId, String source, String action) {
        String base = properties.getClOrdIdPrefix() + action + "-" + shadowSenderCompId + "-" + source;
        if (base.length() > 19) {
            return base.substring(base.length() - 19);
        }
        return base;
    }

    public String getSafeClOrdId(NewOrderSingle order) {
        try {
            return order.getClOrdID().getValue();
        } catch (FieldNotFound e) {
            return "<missing>";
        }
    }


    private static final class PrimaryOrderState {
        private final String orderId;
        private volatile boolean mirrored;
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
