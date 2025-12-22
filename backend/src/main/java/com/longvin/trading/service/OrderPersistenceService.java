package com.longvin.trading.service;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderEvent;
import com.longvin.trading.entities.orders.OrderGroup;
import com.longvin.trading.repository.AccountRepository;
import com.longvin.trading.repository.OrderEventRepository;
import com.longvin.trading.repository.OrderGroupRepository;
import com.longvin.trading.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.FieldNotFound;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Service for persisting ExecutionReports as OrderEvent and Order entities.
 * Follows event sourcing principles: every ExecutionReport becomes an immutable OrderEvent,
 * and Order state is derived from events.
 */
@Service
public class OrderPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistenceService.class);

    private final OrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final OrderGroupRepository orderGroupRepository;
    private final AccountRepository accountRepository;

    public OrderPersistenceService(OrderRepository orderRepository,
                                   OrderEventRepository orderEventRepository,
                                   OrderGroupRepository orderGroupRepository,
                                   AccountRepository accountRepository) {
        this.orderRepository = orderRepository;
        this.orderEventRepository = orderEventRepository;
        this.orderGroupRepository = orderGroupRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Create an OrderEvent from an ExecutionReport and persist it.
     * Also creates or updates the Order entity based on the event.
     * 
     * @param report The ExecutionReport from FIX
     * @param sessionID The FIX session ID where the report came from
     * @return The created OrderEvent
     */
    @Transactional
    public OrderEvent createOrderEvent(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        String execId = report.getExecID().getValue();
        
        // Check if event already exists (idempotency)
        Optional<OrderEvent> existingEvent = orderEventRepository.findByFixExecId(execId);
        if (existingEvent.isPresent()) {
            log.debug("OrderEvent already exists for ExecID={}, skipping", execId);
            return existingEvent.get();
        }

        // Find or create Order entity
        Order order = findOrCreateOrder(report, sessionID);
        
        // Create OrderEvent (immutable event)
        OrderEvent event = buildOrderEvent(report, sessionID, order);
        event = orderEventRepository.save(event);
        
        // Update Order state from event
        updateOrderFromEvent(order, event);
        orderRepository.save(order);
        
        log.debug("Created OrderEvent: ExecID={}, OrderID={}, ExecType={}", 
            execId, order.getFixOrderId(), event.getExecType());
        
        return event;
    }

    /**
     * Find existing Order or create a new one from ExecutionReport.
     */
    private Order findOrCreateOrder(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        String orderId = report.getString(OrderID.FIELD);
        String clOrdId = report.isSetField(ClOrdID.FIELD) ? report.getString(ClOrdID.FIELD) : null;
        
        // Try to find by OrderID first
        Optional<Order> existingOrder = orderRepository.findByFixOrderId(orderId);
        if (existingOrder.isPresent()) {
            return existingOrder.get();
        }
        
        // Try to find by ClOrdID (for new orders before OrderID is assigned)
        if (clOrdId != null) {
            existingOrder = orderRepository.findByFixClOrdId(clOrdId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
        }
        
        // Create new Order
        Order order = buildOrder(report, sessionID);
        
        // Find or create OrderGroup if this is a primary account order
        Account account = order.getAccount();
        if (account != null && account.getAccountType() == AccountType.PRIMARY) {
            OrderGroup orderGroup = findOrCreateOrderGroup(order);
            order.setOrderGroup(orderGroup);
        }
        
        return orderRepository.save(order);
    }

    /**
     * Build Order entity from ExecutionReport.
     */
    private Order buildOrder(ExecutionReport report, SessionID sessionID) throws FieldNotFound {
        Order.OrderBuilder builder = Order.builder();
        
        // FIX IDs
        if (report.isSetField(OrderID.FIELD)) {
            builder.fixOrderId(report.getString(OrderID.FIELD));
        }
        if (report.isSetField(ClOrdID.FIELD)) {
            builder.fixClOrdId(report.getString(ClOrdID.FIELD));
        }
        if (report.isSetField(OrigClOrdID.FIELD)) {
            builder.fixOrigClOrdId(report.getString(OrigClOrdID.FIELD));
        }
        
        // Symbol and side
        if (report.isSetField(Symbol.FIELD)) {
            builder.symbol(report.getString(Symbol.FIELD));
        }
        if (report.isSetField(Side.FIELD)) {
            builder.side(report.getSide().getValue());
        }
        
        // Order type and time in force
        if (report.isSetField(OrdType.FIELD)) {
            builder.ordType(report.getOrdType().getValue());
        }
        if (report.isSetField(TimeInForce.FIELD)) {
            builder.timeInForce(report.getTimeInForce().getValue());
        }
        
        // Quantities and prices
        if (report.isSetField(OrderQty.FIELD)) {
            builder.orderQty(BigDecimal.valueOf(report.getOrderQty().getValue()));
        }
        if (report.isSetField(Price.FIELD)) {
            builder.price(BigDecimal.valueOf(report.getPrice().getValue()));
        }
        if (report.isSetField(StopPx.FIELD)) {
            builder.stopPx(BigDecimal.valueOf(report.getStopPx().getValue()));
        }
        
        // Account
        if (report.isSetField(quickfix.field.Account.FIELD)) {
            String accountNumber = report.getString(quickfix.field.Account.FIELD);
            Optional<Account> account = accountRepository.findByAccountNumber(accountNumber);
            if (account.isPresent()) {
                builder.account(account.get());
            } else {
                log.warn("Account not found for accountNumber={}, order will be created without account", accountNumber);
            }
        }
        
        // Initial status (will be updated from event)
        builder.ordStatus('0'); // NEW
        
        return builder.build();
    }

    /**
     * Build OrderEvent entity from ExecutionReport.
     */
    private OrderEvent buildOrderEvent(ExecutionReport report, SessionID sessionID, Order order) throws FieldNotFound {
        OrderEvent.OrderEventBuilder builder = OrderEvent.builder()
            .order(order)
            .fixExecId(report.getExecID().getValue())
            .execType(report.getExecType().getValue())
            .ordStatus(report.getOrdStatus().getValue())
            .eventTime(LocalDateTime.now())
            .sessionId(sessionID.toString());
        
        // FIX IDs
        if (report.isSetField(OrderID.FIELD)) {
            builder.fixOrderId(report.getString(OrderID.FIELD));
        }
        if (report.isSetField(ClOrdID.FIELD)) {
            builder.fixClOrdId(report.getString(ClOrdID.FIELD));
        }
        if (report.isSetField(OrigClOrdID.FIELD)) {
            builder.fixOrigClOrdId(report.getString(OrigClOrdID.FIELD));
        }
        
        // Symbol and side
        if (report.isSetField(Symbol.FIELD)) {
            builder.symbol(report.getString(Symbol.FIELD));
        }
        if (report.isSetField(Side.FIELD)) {
            builder.side(report.getSide().getValue());
        }
        
        // Order type and time in force
        if (report.isSetField(OrdType.FIELD)) {
            builder.ordType(report.getOrdType().getValue());
        }
        if (report.isSetField(TimeInForce.FIELD)) {
            builder.timeInForce(report.getTimeInForce().getValue());
        }
        
        // Quantities and prices
        if (report.isSetField(OrderQty.FIELD)) {
            builder.orderQty(BigDecimal.valueOf(report.getOrderQty().getValue()));
        }
        if (report.isSetField(Price.FIELD)) {
            builder.price(BigDecimal.valueOf(report.getPrice().getValue()));
        }
        if (report.isSetField(StopPx.FIELD)) {
            builder.stopPx(BigDecimal.valueOf(report.getStopPx().getValue()));
        }
        
        // Execution details
        if (report.isSetField(LastPx.FIELD)) {
            builder.lastPx(BigDecimal.valueOf(report.getLastPx().getValue()));
        }
        if (report.isSetField(LastShares.FIELD)) {
            builder.lastQty(BigDecimal.valueOf(report.getLastShares().getValue()));
        }
        if (report.isSetField(CumQty.FIELD)) {
            builder.cumQty(BigDecimal.valueOf(report.getCumQty().getValue()));
        }
        if (report.isSetField(LeavesQty.FIELD)) {
            builder.leavesQty(BigDecimal.valueOf(report.getLeavesQty().getValue()));
        }
        if (report.isSetField(AvgPx.FIELD)) {
            builder.avgPx(BigDecimal.valueOf(report.getAvgPx().getValue()));
        }
        
        // Account
        if (report.isSetField(quickfix.field.Account.FIELD)) {
            builder.account(report.getString(quickfix.field.Account.FIELD));
        }
        
        // Transaction time
        if (report.isSetField(TransactTime.FIELD)) {
            try {
                TransactTime transactTimeField = report.getTransactTime();
                if (transactTimeField != null) {
                    Object value = transactTimeField.getValue();
                    if (value instanceof java.util.Date) {
                        java.util.Date transactDate = (java.util.Date) value;
                        LocalDateTime transactTime = transactDate.toInstant()
                            .atZone(ZoneOffset.UTC).toLocalDateTime();
                        builder.transactTime(transactTime);
                    } else if (value instanceof java.time.LocalDateTime ldt) {
                        // Some QuickFIX/J builds expose UTC timestamp fields as LocalDateTime
                        builder.transactTime(ldt);
                    } else if (value instanceof java.time.Instant instant) {
                        builder.transactTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
                    } else if (value != null) {
                        log.debug("Unexpected TransactTime type: {}", value.getClass().getName());
                    }
                }
            } catch (Exception e) {
                log.debug("Error parsing TransactTime: {}", e.getMessage());
            }
        }
        
        // Text
        if (report.isSetField(Text.FIELD)) {
            builder.text(report.getString(Text.FIELD));
        }
        
        // Raw FIX message (optional, for debugging)
        builder.rawFixMessage(report.toString());
        
        return builder.build();
    }

    /**
     * Update Order state from OrderEvent.
     */
    private void updateOrderFromEvent(Order order, OrderEvent event) {
        // Update current state fields
        order.setExecType(event.getExecType());
        order.setOrdStatus(event.getOrdStatus());
        
        // Update quantities and prices
        if (event.getCumQty() != null) {
            order.setCumQty(event.getCumQty());
        }
        if (event.getLeavesQty() != null) {
            order.setLeavesQty(event.getLeavesQty());
        }
        if (event.getAvgPx() != null) {
            order.setAvgPx(event.getAvgPx());
        }
        if (event.getLastPx() != null) {
            order.setLastPx(event.getLastPx());
        }
        if (event.getLastQty() != null) {
            order.setLastQty(event.getLastQty());
        }
        
        // Update FIX IDs if changed
        if (event.getFixOrderId() != null) {
            order.setFixOrderId(event.getFixOrderId());
        }
        if (event.getFixClOrdId() != null) {
            order.setFixClOrdId(event.getFixClOrdId());
        }
        if (event.getFixOrigClOrdId() != null) {
            order.setFixOrigClOrdId(event.getFixOrigClOrdId());
        }
    }

    /**
     * Find or create OrderGroup for a primary order.
     */
    private OrderGroup findOrCreateOrderGroup(Order order) {
        Account account = order.getAccount();
        if (account == null || account.getAccountType() != AccountType.PRIMARY) {
            return null;
        }
        
        // Use strategyKey from account if available
        String strategyKey = account.getStrategyKey();
        if (strategyKey == null || strategyKey.isBlank()) {
            // Generate default strategy key
            strategyKey = "PRIMARY_" + account.getAccountNumber();
        }
        
        Optional<OrderGroup> existingGroup = orderGroupRepository.findByStrategyKey(strategyKey);
        if (existingGroup.isPresent()) {
            return existingGroup.get();
        }
        
        // Create new OrderGroup
        OrderGroup group = OrderGroup.builder()
            .strategyKey(strategyKey)
            .primaryOrder(order)
            .build();
        
        return orderGroupRepository.save(group);
    }

    /**
     * Get Order by FIX OrderID.
     */
    public Optional<Order> getOrderByFixOrderId(String fixOrderId) {
        return orderRepository.findByFixOrderId(fixOrderId);
    }

    /**
     * Find order by FIX ClOrdID.
     */
    public Optional<Order> findOrderByClOrdId(String clOrdId) {
        if (clOrdId == null || clOrdId.isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findByFixClOrdId(clOrdId);
    }
}

