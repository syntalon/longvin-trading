package com.longvin.trading.service;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.entities.orders.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.NewOrderSingle;
import quickfix.fix42.OrderCancelReplaceRequest;
import quickfix.fix42.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for replicating drop copy ExecutionReports to shadow accounts via initiator sessions.
 * When the acceptor receives an ExecutionReport from DAS Trader, this service replicates
 * the order to all configured shadow sessions.
 */
@Service
public class DropCopyReplicationService {
    
    private static final Logger log = LoggerFactory.getLogger(DropCopyReplicationService.class);
    
    private final FixClientProperties properties;
    private final Executor executor;
    private final ShortOrderProcessingService shortOrderProcessingService;
    private final OrderPersistenceService orderPersistenceService;
    private final ShortOrderDraftService draftService;
    private final ConcurrentMap<String, PrimaryOrderState> primaryOrders = new ConcurrentHashMap<>();
    private final Set<String> processedExecIds = ConcurrentHashMap.newKeySet();
    
    public DropCopyReplicationService(FixClientProperties properties,
                                     @Qualifier("orderMirroringExecutor") Executor executor,
                                     ShortOrderProcessingService shortOrderProcessingService,
                                     OrderPersistenceService orderPersistenceService,
                                     ShortOrderDraftService draftService) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.shortOrderProcessingService = Objects.requireNonNull(shortOrderProcessingService, "shortOrderProcessingService must not be null");
        this.orderPersistenceService = Objects.requireNonNull(orderPersistenceService, "orderPersistenceService must not be null");
        this.draftService = Objects.requireNonNull(draftService, "draftService must not be null");
    }
    
    /**
     * Process an ExecutionReport from the drop copy session.
     * This will replicate new/replace/cancel orders to shadow accounts via the initiator session.
     * All shadow accounts use the same initiator session, differentiated by Account field.
     * 
     * @param report The ExecutionReport received from DAS Trader
     * @param sessionID The session ID (should be the drop copy acceptor session)
     * @param initiatorSessionID The initiator session ID to use for sending replicated orders (e.g., OS111->OPAL)
     * @throws FieldNotFound if required fields are missing
     */
    public void processExecutionReport(ExecutionReport report, SessionID sessionID, SessionID initiatorSessionID) 
            throws FieldNotFound {
        
        // Check if this is from the drop copy session
        if (!sessionID.getSenderCompID().equalsIgnoreCase(properties.getDropCopySessionSenderCompId())
                || !sessionID.getTargetCompID().equalsIgnoreCase(properties.getDropCopySessionTargetCompId())) {
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

        // Persist ExecutionReport as OrderEvent and Order
        try {
            orderPersistenceService.createOrderEvent(report, sessionID);
        } catch (Exception e) {
            log.error("Error persisting ExecutionReport: {}", e.getMessage(), e);
            // Continue processing even if persistence fails
        }
        
        char execType = report.getExecType().getValue();
        if (execType == ExecType.NEW) {
            handleNewExecution(report, account, orderId, initiatorSessionID);
        } else if (execType == ExecType.REPLACED) {
            handleReplaceExecution(report, orderId, initiatorSessionID);
        } else if (execType == ExecType.CANCELED) {
            handleCancelExecution(orderId, initiatorSessionID);
        } else {
            log.trace("Observed execution {} type {} for order {}", execId, execType, orderId);
        }
    }
    
    private void handleNewExecution(ExecutionReport report, String account, String orderId, SessionID initiatorSessionID) 
            throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.computeIfAbsent(orderId, PrimaryOrderState::new);
        state.updateFrom(report, account);
        if (!state.markMirrored()) {
            return;
        }
        
        // Check if this is a short order
        if (state.side != null && shortOrderProcessingService.isShortOrder(state.side)) {
            log.info("Detected short order for orderId={}, symbol={}, side={}, qty={}", 
                orderId, state.symbol, state.side, state.orderQty);
            
            // Step 1: Create draft orders for shadow accounts immediately
            createDraftOrdersForShortOrder(orderId);
            // Always go through locate workflow for short orders
            log.info("Short order locate workflow enabled for all symbols. Continuing locate workflow for orderId={}", orderId);
            processShortOrderWithLocate(state, account, orderId, initiatorSessionID);
        } else {
            // Regular order - replicate directly to shadows
            replicateNewOrderToShadows(state, initiatorSessionID);
        }
    }
    
    /**
     * Create draft orders for shadow accounts when a short order is detected.
     * These orders will be sent after locate is approved and stock is borrowed.
     */
    private void createDraftOrdersForShortOrder(String orderId) {
        try {
            Optional<Order> primaryOrderOpt = orderPersistenceService.getOrderByFixOrderId(orderId);
            if (primaryOrderOpt.isEmpty()) {
                log.warn("Primary order not found for orderId={}, cannot create draft orders", orderId);
                return;
            }

            Order primaryOrder = primaryOrderOpt.get();
            List<Order> draftOrders = draftService.createDraftOrdersForShadowAccounts(primaryOrder);
            log.info("Created {} draft orders for shadow accounts based on primary order {}", 
                draftOrders.size(), orderId);
        } catch (Exception e) {
            log.error("Error creating draft orders for short order orderId={}", orderId, e);
        }
    }

    /**
     * Process a short order: send locate request, wait for response, borrow stock, then place shadow orders.
     */
    private void processShortOrderWithLocate(PrimaryOrderState state, String account, String orderId, SessionID initiatorSessionID) {
        if (state.symbol == null || state.orderQty == null) {
            log.warn("Cannot process short order: missing symbol or quantity for orderId={}", orderId);
            return;
        }
        
        // Get Order entity from database (should have been persisted by OrderPersistenceService)
        Optional<Order> orderOpt = orderPersistenceService.getOrderByFixOrderId(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("Order not found in database for orderId={}, cannot process short order locate request", orderId);
            return;
        }
        
        Order order = orderOpt.get();
        
        // Check if order is actually short
        if (order.getSide() == null || !shortOrderProcessingService.isShortOrder(order.getSide())) {
            log.warn("Order {} is not a short order (side={}), skipping locate request", orderId, order.getSide());
            return;
        }
        
        log.info("Processing short order locate request: OrderId={}, Symbol={}, Qty={}", 
            orderId, state.symbol, state.orderQty);
        
        // Process short order with locate request
        try {
            shortOrderProcessingService.processShortOrder(
                order,
                state.symbol,
                state.orderQty,
                initiatorSessionID
            );
            log.info("Locate request sent for short order: OrderId={}, Symbol={}, Qty={}", 
                orderId, state.symbol, state.orderQty);
        } catch (Exception e) {
            log.error("Error processing short order locate request for orderId={}", orderId, e);
        }
    }

    private void handleReplaceExecution(ExecutionReport report, String orderId, SessionID initiatorSessionID) 
            throws FieldNotFound {
        PrimaryOrderState state = primaryOrders.get(orderId);
        if (state == null) {
            log.warn("Received replace exec report for unknown order {}", orderId);
            return;
        }
        state.updateFrom(report, state.account);
        replicateReplaceToShadows(state, initiatorSessionID);
    }

    private void handleCancelExecution(String orderId, SessionID initiatorSessionID) {
        PrimaryOrderState state = primaryOrders.get(orderId);
        if (state == null) {
            log.warn("Received cancel exec report for unknown order {}", orderId);
            return;
        }
        replicateCancelToShadows(state, initiatorSessionID);
        primaryOrders.remove(orderId);
    }
    
    private void replicateNewOrderToShadows(final PrimaryOrderState state, SessionID initiatorSessionID) {
        if (properties.getShadowSessions().isEmpty()) {
            log.debug("No shadow accounts configured; skipping drop-copy mirror for order {}", state.orderId);
            return;
        }
        // Use the same initiator session for all shadow accounts, differentiated by Account field
        // Send orders concurrently for different accounts using the same session
        for (final String shadowAccountName : properties.getShadowSessions()) {
            final String shadowAccount = properties.getShadowAccounts().get(shadowAccountName);
            if (shadowAccount == null) {
                log.warn("No account configured for shadow account {}, skipping", shadowAccountName);
                continue;
            }
            
            // Execute replication for each shadow account concurrently
            executor.execute(() -> {
                try {
                    final ShadowOrderState shadowState = state.shadows.computeIfAbsent(shadowAccountName, id -> new ShadowOrderState());
                    final String clOrdId = generateMirrorClOrdId(shadowAccountName, state.orderId, "N");
                    final NewOrderSingle mirroredOrder = buildMirroredNewOrder(state, clOrdId, shadowAccountName);
                    if (mirroredOrder == null) {
                        return;
                    }
                    // Set the Account field for this shadow account
                    mirroredOrder.setField(new Account(shadowAccount));
                    Session.sendToTarget(mirroredOrder, initiatorSessionID);
                    shadowState.currentClOrdId = clOrdId;
                    log.info("Drop-copy mirrored order {} -> shadow account {} (Account={}, ClOrdID={})", 
                        state.orderId, shadowAccountName, shadowAccount, clOrdId);
                } catch (SessionNotFound ex) {
                    log.error("Failed to send mirrored order {} to shadow account {}, reason: {}", 
                        state.orderId, shadowAccountName, ex.getMessage(), ex);
                } catch (Exception e) {
                    log.error("Unexpected error replicating order {} to shadow account {}: {}", 
                        state.orderId, shadowAccountName, e.getMessage(), e);
                }
            });
        }
    }

    private void replicateReplaceToShadows(final PrimaryOrderState state, SessionID initiatorSessionID) {
        state.shadows.forEach((shadowAccountName, shadowState) -> {
            // Capture currentClOrdId before submitting to executor to avoid race conditions
            final String currentClOrdId = shadowState.currentClOrdId;
            if (currentClOrdId == null) {
                log.debug("No known shadow order for {} on shadow account {}; skipping replace", state.orderId, shadowAccountName);
                return;
            }
            final String shadowAccount = properties.getShadowAccounts().get(shadowAccountName);
            if (shadowAccount == null) {
                log.warn("No account configured for shadow account {}, skipping replace", shadowAccountName);
                return;
            }
            
            // Execute replace for each shadow account concurrently
            executor.execute(() -> {
                try {
                    final String clOrdId = generateMirrorClOrdId(shadowAccountName, state.orderId, "R");
                    final OrderCancelReplaceRequest replace = buildMirroredReplace(state, clOrdId, currentClOrdId, shadowAccountName);
                    // Set the Account field for this shadow account
                    replace.setField(new Account(shadowAccount));
                    Session.sendToTarget(replace, initiatorSessionID);
                    shadowState.currentClOrdId = clOrdId;
                    log.info("Drop-copy mirrored replace for order {} on shadow account {} (Account={}, new ClOrdID={})", 
                        state.orderId, shadowAccountName, shadowAccount, clOrdId);
                } catch (SessionNotFound ex) {
                    log.error("Failed to send mirrored replace for order {} to shadow account {}, reason: {}", 
                        state.orderId, shadowAccountName, ex.getMessage(), ex);
                } catch (Exception e) {
                    log.error("Unexpected error replicating replace for order {} to shadow account {}: {}", 
                        state.orderId, shadowAccountName, e.getMessage(), e);
                }
            });
        });
    }

    private void replicateCancelToShadows(final PrimaryOrderState state, SessionID initiatorSessionID) {
        state.shadows.forEach((shadowAccountName, shadowState) -> {
            // Capture currentClOrdId before submitting to executor to avoid race conditions
            final String currentClOrdId = shadowState.currentClOrdId;
            if (currentClOrdId == null) {
                log.debug("No known shadow order for {} on shadow account {}; skipping cancel", state.orderId, shadowAccountName);
                return;
            }
            final String shadowAccount = properties.getShadowAccounts().get(shadowAccountName);
            if (shadowAccount == null) {
                log.warn("No account configured for shadow account {}, skipping cancel", shadowAccountName);
                return;
            }
            
            // Execute cancel for each shadow account concurrently
            executor.execute(() -> {
                try {
                    final String clOrdId = generateMirrorClOrdId(shadowAccountName, state.orderId, "C");
                    final OrderCancelRequest cancel = buildMirroredCancel(state, clOrdId, currentClOrdId, shadowAccountName);
                    // Set the Account field for this shadow account
                    cancel.setField(new Account(shadowAccount));
                    Session.sendToTarget(cancel, initiatorSessionID);
                    log.info("Drop-copy mirrored cancel for order {} on shadow account {} (Account={})", 
                        state.orderId, shadowAccountName, shadowAccount);
                } catch (SessionNotFound ex) {
                    log.error("Failed to send mirrored cancel for order {} to shadow account {}, reason: {}", 
                        state.orderId, shadowAccountName, ex.getMessage(), ex);
                } catch (Exception e) {
                    log.error("Unexpected error replicating cancel for order {} to shadow account {}: {}", 
                        state.orderId, shadowAccountName, e.getMessage(), e);
                }
            });
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

    // Note: Account field is now set directly in replicate methods, this method is kept for compatibility
    private void overrideAccountIfNeeded(Message order, String shadowAccountName) {
        Map<String, String> overrides = properties.getShadowAccounts();
        if (overrides.isEmpty()) {
            return;
        }
        Optional.ofNullable(overrides.get(shadowAccountName)).ifPresent(account -> order.setField(new Account(account)));
    }

    private String generateMirrorClOrdId(String shadowSenderCompId, String source, String action) {
        String base = properties.getClOrdIdPrefix() + action + "-" + shadowSenderCompId + "-" + source;
        if (base.length() > 19) {
            return base.substring(base.length() - 19);
        }
        return base;
    }

    /**
     * Internal state for tracking a primary order from drop copy.
     */
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

    /**
     * Internal state for tracking a shadow order.
     */
    private static final class ShadowOrderState {
        private volatile String currentClOrdId;
    }
}

