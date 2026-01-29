package com.longvin.trading.service;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderEvent;
import com.longvin.trading.entities.orders.OrderGroup;
import com.longvin.trading.repository.OrderGroupRepository;
import com.longvin.trading.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.SessionID;

import java.util.Optional;

/**
 * Service for managing order persistence and order events.
 * Handles creation and updates of Order entities and OrderEvent records.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderGroupRepository orderGroupRepository;
    private final AccountCacheService accountCacheService;

    public OrderService(OrderRepository orderRepository, 
                       OrderGroupRepository orderGroupRepository,
                       AccountCacheService accountCacheService) {
        this.orderRepository = orderRepository;
        this.orderGroupRepository = orderGroupRepository;
        this.accountCacheService = accountCacheService;
    }

    /**
     * Create or update order from ExecutionReportContext for a primary account.
     * Also creates an OrderEvent for audit trail.
     * 
     * Validates that:
     * - The account exists and is a PRIMARY account type
     * 
     * @param context ExecutionReportContext containing order information
     * @param sessionID FIX session ID
     * @return Created or updated Order entity, or null if validation fails
     */
    @Transactional
    public Order createOrUpdateOrderForPrimaryAccount(ExecutionReportContext context, SessionID sessionID) {
        // Validate account exists and is primary
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot persist order. ClOrdID={}", 
                    context.getClOrdID());
            return null;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty()) {
            log.warn("Account not found for order: ClOrdID={}, Account: {}", 
                    context.getClOrdID(), context.getAccount());
            return null;
        }
        
        Account account = accountOpt.get();
        if (account.getAccountType() != AccountType.PRIMARY) {
            log.debug("Skipping order persistence - not a primary account. ClOrdID={}, Account={}, AccountType={}", 
                    context.getClOrdID(), context.getAccount(), account.getAccountType());
            return null;
        }
        
        // Check if order already exists
        Optional<Order> existingOrderOpt = orderRepository.findByFixClOrdId(context.getClOrdID());
        
        Order order;
        if (existingOrderOpt.isPresent()) {
            order = existingOrderOpt.get();
            log.debug("Order already exists, updating: ClOrdID={}, OrderID={}", 
                    context.getClOrdID(), context.getOrderID());
        } else {
            // Create new order
            order = buildOrderFromContext(context);
            order.setAccount(account);
            
            // Create or find OrderGroup for this primary order
            OrderGroup orderGroup = findOrCreateOrderGroup(order, account);
            
            // Link order to group (this sets order.orderGroup and adds order to group.orders)
            orderGroup.addOrder(order);
            
            // Save order (this will persist the relationship)
            order = orderRepository.save(order);
            log.info("Created new order: ClOrdID={}, OrderID={}, Account={}, Symbol={}, OrderGroupId={}", 
                    order.getFixClOrdId(), order.getFixOrderId(), 
                    order.getAccount().getAccountNumber(), order.getSymbol(), orderGroup.getId());
        }
        
        // Update order fields from context
        updateOrderFromContext(order, context);
        order = orderRepository.save(order);
        
        // Create order event
        createOrderEvent(order, context, sessionID);
        
        return order;
    }

    /**
     * Build Order entity from ExecutionReportContext.
     */
    private Order buildOrderFromContext(ExecutionReportContext context) {
        Order order = Order.builder()
                .fixClOrdId(context.getClOrdID())
                .fixOrderId(context.getOrderID())
                .symbol(context.getSymbol())
                .side(context.getSide())
                .orderQty(context.getOrderQty())
                .ordType(context.getOrdType())
                .timeInForce(context.getTimeInForce())
                .price(context.getPrice())
                .stopPx(context.getStopPx())
                .exDestination(context.getExDestination())
                .execType(context.getExecType())
                .ordStatus(context.getOrdStatus())
                .cumQty(context.getCumQty())
                .leavesQty(context.getLeavesQty())
                .avgPx(context.getAvgPx())
                .lastPx(context.getLastPx())
                .lastQty(context.getLastQty())
                .build();
        
        return order;
    }

    /**
     * Update Order entity with latest values from ExecutionReportContext.
     */
    private void updateOrderFromContext(Order order, ExecutionReportContext context) {
        order.setFixOrderId(context.getOrderID());
        order.setSymbol(context.getSymbol());
        order.setSide(context.getSide());
        order.setOrderQty(context.getOrderQty());
        order.setOrdType(context.getOrdType());
        order.setTimeInForce(context.getTimeInForce());
        order.setPrice(context.getPrice());
        order.setStopPx(context.getStopPx());
        order.setExDestination(context.getExDestination());
        order.setExecType(context.getExecType());
        order.setOrdStatus(context.getOrdStatus());
        order.setCumQty(context.getCumQty());
        order.setLeavesQty(context.getLeavesQty());
        order.setAvgPx(context.getAvgPx());
        order.setLastPx(context.getLastPx());
        order.setLastQty(context.getLastQty());
    }

    /**
     * Create OrderEvent from ExecutionReportContext.
     */
    private void createOrderEvent(Order order, ExecutionReportContext context, SessionID sessionID) {
        OrderEvent event = OrderEvent.builder()
                .order(order)
                .fixExecId(context.getOrderID() != null ? context.getOrderID() : 
                        context.getClOrdID() + "-" + System.currentTimeMillis()) // Use OrderID as ExecID, fallback to ClOrdID + timestamp
                .execType(context.getExecType())
                .ordStatus(context.getOrdStatus())
                .fixOrderId(context.getOrderID())
                .fixClOrdId(context.getClOrdID())
                .fixOrigClOrdId(context.getOrigClOrdID())
                .symbol(context.getSymbol())
                .side(context.getSide())
                .ordType(context.getOrdType())
                .timeInForce(context.getTimeInForce())
                .orderQty(context.getOrderQty())
                .price(context.getPrice())
                .stopPx(context.getStopPx())
                .lastPx(context.getLastPx())
                .lastQty(context.getLastQty())
                .cumQty(context.getCumQty())
                .leavesQty(context.getLeavesQty())
                .avgPx(context.getAvgPx())
                .account(context.getAccount())
                .transactTime(context.getTransactTime())
                .text(context.getText())
                .sessionId(sessionID != null ? sessionID.toString() : null)
                .build();
        
        order.addEvent(event);
        log.debug("Created order event: ClOrdID={}, ExecType={}, OrdStatus={}", 
                context.getClOrdID(), context.getExecType(), context.getOrdStatus());
    }

    /**
     * Find or create OrderGroup for a primary order.
     * Uses the account's strategyKey if available, otherwise generates one.
     */
    private OrderGroup findOrCreateOrderGroup(Order order, Account account) {
        // Use strategyKey from account if available
        String strategyKey = account.getStrategyKey();
        if (strategyKey == null || strategyKey.isBlank()) {
            // Generate default strategy key based on account number
            strategyKey = "PRIMARY_" + account.getAccountNumber();
        }
        
        // Try to find existing group by strategy key
        Optional<OrderGroup> existingGroup = orderGroupRepository.findByStrategyKey(strategyKey);
        if (existingGroup.isPresent()) {
            OrderGroup group = existingGroup.get();
            log.debug("Found existing OrderGroup: strategyKey={}, OrderGroupId={}", 
                    strategyKey, group.getId());
            return group;
        }
        
        // Create new OrderGroup
        OrderGroup group = OrderGroup.builder()
                .strategyKey(strategyKey)
                .primaryOrder(order)
                .symbol(order.getSymbol())
                .totalTargetQty(order.getOrderQty())
                .state(OrderGroup.GroupState.ACTIVE) // Primary order is active
                .build();
        
        group = orderGroupRepository.save(group);
        
        log.info("Created new OrderGroup: strategyKey={}, OrderGroupId={}, PrimaryOrderClOrdID={}", 
                strategyKey, group.getId(), order.getFixClOrdId());
        
        return group;
    }

    /**
     * Create a shadow account order and link it to the primary order's OrderGroup.
     * This is used when copying orders to shadow accounts.
     * 
     * @param primaryOrderClOrdId The ClOrdID of the primary order
     * @param shadowAccount The shadow account
     * @param shadowClOrdId The ClOrdID for the shadow order (e.g., "COPY-{account}-{primaryClOrdId}")
     * @param symbol Order symbol
     * @param side Order side
     * @param ordType Order type
     * @param orderQty Order quantity
     * @param price Order price (nullable)
     * @param stopPx Stop price (nullable)
     * @param timeInForce Time in force
     * @param exDestination Route/destination
     * @return Created Order entity, or null if primary order not found
     */
    @Transactional
    public Order createShadowAccountOrder(String primaryOrderClOrdId, Account shadowAccount,
                                          String shadowClOrdId, String symbol, Character side,
                                          Character ordType, java.math.BigDecimal orderQty,
                                          java.math.BigDecimal price, java.math.BigDecimal stopPx,
                                          Character timeInForce, String exDestination) {
        // Find primary order to get its OrderGroup
        Optional<Order> primaryOrderOpt = orderRepository.findByFixClOrdId(primaryOrderClOrdId);
        if (primaryOrderOpt.isEmpty()) {
            log.warn("Primary order not found for ClOrdID={}, cannot create shadow order. ShadowClOrdID={}", 
                    primaryOrderClOrdId, shadowClOrdId);
            return null;
        }
        
        Order primaryOrder = primaryOrderOpt.get();
        OrderGroup orderGroup = primaryOrder.getOrderGroup();
        
        if (orderGroup == null) {
            log.warn("Primary order has no OrderGroup, cannot link shadow order. PrimaryClOrdID={}, ShadowClOrdID={}", 
                    primaryOrderClOrdId, shadowClOrdId);
            return null;
        }
        
        // Check if shadow order already exists
        Optional<Order> existingShadowOrderOpt = orderRepository.findByFixClOrdId(shadowClOrdId);
        if (existingShadowOrderOpt.isPresent()) {
            log.debug("Shadow order already exists: ShadowClOrdID={}", shadowClOrdId);
            return existingShadowOrderOpt.get();
        }
        
        // Create shadow order
        Order shadowOrder = Order.builder()
                .fixClOrdId(shadowClOrdId)
                .fixOrderId(null) // Will be set when ExecutionReport is received
                .account(shadowAccount)
                .orderGroup(orderGroup)
                .symbol(symbol)
                .side(side)
                .ordType(ordType)
                .orderQty(orderQty)
                .price(price)
                .stopPx(stopPx)
                .timeInForce(timeInForce)
                .exDestination(exDestination)
                .execType('0') // NEW
                .ordStatus('0') // NEW
                .build();
        
        // Link to OrderGroup
        orderGroup.addOrder(shadowOrder);
        
        // Save shadow order
        shadowOrder = orderRepository.save(shadowOrder);
        
        log.info("Created shadow account order: ShadowClOrdID={}, ShadowAccount={}, PrimaryClOrdID={}, OrderGroupId={}", 
                shadowClOrdId, shadowAccount.getAccountNumber(), primaryOrderClOrdId, orderGroup.getId());
        
        return shadowOrder;
    }

    /**
     * Update copy order (shadow account order) and create OrderEvent.
     * This is called when a copy order ExecutionReport is received.
     * 
     * If the order doesn't exist yet (race condition where fill arrives before order is persisted),
     * it will create the order first, link it to the primary order's OrderGroup, then update it and create the event.
     * 
     * @param context ExecutionReportContext containing order information
     * @param sessionID FIX session ID
     * @return Updated Order entity, or null if validation fails
     */
    @Transactional
    public Order updateCopyOrderAndCreateEvent(ExecutionReportContext context, SessionID sessionID) {
        if (context.getClOrdID() == null || !context.getClOrdID().startsWith("COPY-")) {
            log.warn("Not a copy order, cannot update. ClOrdID={}", context.getClOrdID());
            return null;
        }
        
        // Validate account exists and is shadow
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot persist copy order. ClOrdID={}", 
                    context.getClOrdID());
            return null;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty()) {
            log.warn("Account not found for copy order: ClOrdID={}, Account: {}", 
                    context.getClOrdID(), context.getAccount());
            return null;
        }
        
        Account shadowAccount = accountOpt.get();
        if (shadowAccount.getAccountType() != AccountType.SHADOW) {
            log.debug("Not a shadow account, cannot update copy order. ClOrdID={}, Account={}, AccountType={}", 
                    context.getClOrdID(), context.getAccount(), shadowAccount.getAccountType());
            return null;
        }
        
        // Find shadow order by ClOrdID
        Optional<Order> orderOpt = orderRepository.findByFixClOrdId(context.getClOrdID());
        
        Order order;
        if (orderOpt.isPresent()) {
            // Order exists - update it
            order = orderOpt.get();
            log.debug("Copy order exists, updating: ClOrdID={}, OrderID={}", 
                    context.getClOrdID(), context.getOrderID());
        } else {
            // Order doesn't exist yet (race condition) - create it
            log.info("Copy order not found in database, creating it (race condition). ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}", 
                    context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus());
            
            // Extract primary ClOrdID from shadow ClOrdID (format: "COPY-{shadowAccount}-{primaryClOrdID}")
            String primaryClOrdId = extractPrimaryClOrdIdFromShadow(context.getClOrdID());
            if (primaryClOrdId == null) {
                log.warn("Cannot extract primary ClOrdID from shadow ClOrdID: ClOrdID={}", context.getClOrdID());
                return null;
            }
            
            // Find primary order to get its OrderGroup
            Optional<Order> primaryOrderOpt = orderRepository.findByFixClOrdId(primaryClOrdId);
            if (primaryOrderOpt.isEmpty()) {
                log.warn("Primary order not found for shadow order: PrimaryClOrdID={}, ShadowClOrdID={}", 
                        primaryClOrdId, context.getClOrdID());
                return null;
            }
            
            Order primaryOrder = primaryOrderOpt.get();
            OrderGroup orderGroup = primaryOrder.getOrderGroup();
            
            if (orderGroup == null) {
                log.warn("Primary order has no OrderGroup, cannot link shadow order. PrimaryClOrdID={}, ShadowClOrdID={}", 
                        primaryClOrdId, context.getClOrdID());
                return null;
            }
            
            // Create shadow order
            order = buildOrderFromContext(context);
            order.setAccount(shadowAccount);
            order.setOrderGroup(orderGroup);
            
            // Link to OrderGroup
            orderGroup.addOrder(order);
            
            // Save shadow order
            order = orderRepository.save(order);
            log.info("Created copy order from fill ExecutionReport: ClOrdID={}, OrderID={}, Account={}, Symbol={}, OrderGroupId={}", 
                    order.getFixClOrdId(), order.getFixOrderId(), 
                    order.getAccount().getAccountNumber(), order.getSymbol(), orderGroup.getId());
        }
        
        // Update order fields from context
        updateOrderFromContext(order, context);
        order = orderRepository.save(order);
        
        // Create order event
        createOrderEvent(order, context, sessionID);
        
        log.debug("Updated copy order and created event: ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}", 
                context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus());
        
        return order;
    }
    
    /**
     * Extract primary ClOrdID from shadow ClOrdID.
     * Shadow ClOrdID format: "COPY-{shadowAccount}-{primaryClOrdID}"
     * 
     * @param shadowClOrdId Shadow order ClOrdID
     * @return Primary ClOrdID, or null if format is invalid
     */
    private String extractPrimaryClOrdIdFromShadow(String shadowClOrdId) {
        if (shadowClOrdId == null || !shadowClOrdId.startsWith("COPY-")) {
            return null;
        }
        
        // Remove "COPY-" prefix
        String remaining = shadowClOrdId.substring(5);
        
        // Find the first "-" which separates shadow account from primary ClOrdID
        int firstDashIndex = remaining.indexOf('-');
        if (firstDashIndex == -1 || firstDashIndex == remaining.length() - 1) {
            return null;
        }
        
        // Extract primary ClOrdID (everything after the first dash)
        return remaining.substring(firstDashIndex + 1);
    }

    /**
     * Update primary order and create OrderEvent from ExecutionReportContext.
     * This is called when a primary order ExecutionReport is received (fill, partial fill, etc.).
     * 
     * If the order doesn't exist yet (race condition where fill arrives before new order confirmation),
     * it will create the order first, then update it and create the event.
     * 
     * @param context ExecutionReportContext containing order information
     * @param sessionID FIX session ID
     * @return Updated Order entity, or null if validation fails
     */
    @Transactional
    public Order updatePrimaryOrderAndCreateEvent(ExecutionReportContext context, SessionID sessionID) {
        if (context.getClOrdID() == null || context.getClOrdID().startsWith("COPY-")) {
            log.warn("Not a primary order, cannot update. ClOrdID={}", context.getClOrdID());
            return null;
        }
        
        // Validate account exists and is primary
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot persist order. ClOrdID={}", 
                    context.getClOrdID());
            return null;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty()) {
            log.warn("Account not found for order: ClOrdID={}, Account: {}", 
                    context.getClOrdID(), context.getAccount());
            return null;
        }
        
        Account account = accountOpt.get();
        if (account.getAccountType() != AccountType.PRIMARY) {
            log.debug("Not a primary account, cannot update. ClOrdID={}, Account={}, AccountType={}", 
                    context.getClOrdID(), context.getAccount(), account.getAccountType());
            return null;
        }
        
        // Find primary order by ClOrdID
        Optional<Order> orderOpt = orderRepository.findByFixClOrdId(context.getClOrdID());
        
        Order order;
        if (orderOpt.isPresent()) {
            // Order exists - update it
            order = orderOpt.get();
            log.debug("Primary order exists, updating: ClOrdID={}, OrderID={}", 
                    context.getClOrdID(), context.getOrderID());
        } else {
            // Order doesn't exist yet (race condition) - create it
            log.info("Primary order not found in database, creating it (race condition). ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}", 
                    context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus());
            
            order = buildOrderFromContext(context);
            order.setAccount(account);
            
            // Create or find OrderGroup for this primary order
            OrderGroup orderGroup = findOrCreateOrderGroup(order, account);
            orderGroup.addOrder(order);
            
            // Save order
            order = orderRepository.save(order);
            log.info("Created primary order from fill ExecutionReport: ClOrdID={}, OrderID={}, Account={}, Symbol={}, OrderGroupId={}", 
                    order.getFixClOrdId(), order.getFixOrderId(), 
                    order.getAccount().getAccountNumber(), order.getSymbol(), orderGroup.getId());
        }
        
        // Update order fields from context
        updateOrderFromContext(order, context);
        order = orderRepository.save(order);
        
        // Create order event
        createOrderEvent(order, context, sessionID);
        
        log.debug("Updated primary order and created event: ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}", 
                context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus());
        
        return order;
    }

    /**
     * Find order from database by OrderID or ClOrdID.
     * Also updates the order with ExDestination if available in the context.
     * 
     * @param context ExecutionReportContext containing order information
     * @return Found Order entity, or null if not found
     */
    @Transactional
    public Order findOrderFromContext(ExecutionReportContext context) {
        Order order = null;
        if (context.getOrderID() != null) {
            Optional<Order> orderOpt = orderRepository.findByFixOrderId(context.getOrderID());
            if (orderOpt.isPresent()) {
                order = orderOpt.get();
            }
        }
        if (order == null && context.getClOrdID() != null) {
            Optional<Order> orderOpt = orderRepository.findByFixClOrdId(context.getClOrdID());
            if (orderOpt.isPresent()) {
                order = orderOpt.get();
            }
        }
        
        // Update ExDestination in the order if available in context and not already set
        if (order != null && context.getExDestination() != null && !context.getExDestination().isBlank()) {
            if (order.getExDestination() == null || order.getExDestination().isBlank()) {
                order.setExDestination(context.getExDestination());
                orderRepository.save(order);
                Long accountId = order.getAccount() != null ? order.getAccount().getId() : null;
                log.info("Stored ExDestination in Order entity: ClOrdID={}, Account: {}, AccountId: {}, ExDestination={}", 
                        context.getClOrdID(), context.getAccount(), accountId, context.getExDestination());
            }
        } else if (order != null && (context.getExDestination() == null || context.getExDestination().isBlank())) {
            Long accountId = order.getAccount() != null ? order.getAccount().getId() : null;
            log.debug("ExecutionReport does not include ExDestination for ClOrdID={}, AccountId: {}, will use stored value if available", 
                    context.getClOrdID(), accountId);
        }
        
        return order;
    }

    /**
     * Check if an order exists by ClOrdID.
     * 
     * @param clOrdId The ClOrdID to check
     * @return true if order exists, false otherwise
     */
    public boolean orderExists(String clOrdId) {
        if (clOrdId == null || clOrdId.isBlank()) {
            return false;
        }
        return orderRepository.findByFixClOrdId(clOrdId).isPresent();
    }
}

