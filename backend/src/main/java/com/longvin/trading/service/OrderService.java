package com.longvin.trading.service;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderEvent;
import com.longvin.trading.repository.OrderEventRepository;
import com.longvin.trading.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.SessionID;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Service for managing order persistence and order events.
 * Handles creation and updates of Order entities and OrderEvent records.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final AccountCacheService accountCacheService;
    private final Executor orderMirroringExecutor;

    public OrderService(OrderRepository orderRepository, 
                       OrderEventRepository orderEventRepository,
                       AccountCacheService accountCacheService,
                       @Qualifier("orderMirroringExecutor") Executor orderMirroringExecutor) {
        this.orderRepository = orderRepository;
        this.orderEventRepository = orderEventRepository;
        this.accountCacheService = accountCacheService;
        this.orderMirroringExecutor = orderMirroringExecutor;
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
            
            // Save order
            order = orderRepository.save(order);
            log.info("Created new order: ClOrdID={}, OrderID={}, Account={}, Symbol={}", 
                    order.getFixClOrdId(), order.getFixOrderId(), 
                    order.getAccount().getAccountNumber(), order.getSymbol());
            
            // Link order to any existing events that were created before the order (event-driven)
            linkOrderToEvents(order);
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
     * Order can be null for event-driven architecture.
     * For shadow account orders, we only create events and don't update the order.
     */
    private OrderEvent createOrderEvent(Order order, ExecutionReportContext context, SessionID sessionID) {
        OrderEvent event = OrderEvent.builder()
                .order(order) // Can be null - event can exist independently
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
        
        // Save event directly (event-driven: events can exist independently of orders)
        event = orderEventRepository.save(event);
        
        // For shadow account orders, we don't update the order - only create events
        // The order was already created when the message was sent, and we don't want to modify it
        // Events are linked via ClOrdID, so they can be queried together without updating the order
        // Only link event to order if it's a primary account order (for backward compatibility)
        if (order != null && order.getAccount() != null && order.getAccount().getAccountType() == AccountType.PRIMARY) {
            order.addEvent(event);
            orderRepository.save(order);
        }
        
        log.debug("Created order event: ClOrdID={}, ExecType={}, OrdStatus={}, OrderId={}, IsShadowAccount={}", 
                context.getClOrdID(), context.getExecType(), context.getOrdStatus(), 
                order != null ? order.getId() : "null",
                order != null && order.getAccount() != null && order.getAccount().getAccountType() == AccountType.SHADOW);
        
        return event;
    }

    /**
     * Create OrderEvent directly from ExecutionReportContext without requiring an order.
     * This is the core method for event-driven architecture.
     * Events can be created even if the order doesn't exist yet.
     * 
     * Events are linked to orders using ClOrdID (not OrderID) because:
     * - ClOrdID is always present in ExecutionReports
     * - OrderID may not be present in the first "New" ExecutionReport
     * - ClOrdID is controlled by us (client), making it reliable for linking
     * 
     * @param context ExecutionReportContext containing event information
     * @param sessionID FIX session ID
     * @return Created OrderEvent
     */
    @Transactional
    public OrderEvent createEventFromExecutionReport(ExecutionReportContext context, SessionID sessionID) {
        // Try to find the order if it exists, but don't require it
        // Use ClOrdID for linking (not OrderID) because ClOrdID is always present
        Order order = null;
        if (context.getClOrdID() != null) {
            order = findOrderByClOrdId(context.getClOrdID());
        }
        
        // Create event (order can be null)
        OrderEvent event = createOrderEvent(order, context, sessionID);
        
        log.info("Created event from ExecutionReport (event-driven): ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}, OrderExists={}", 
                context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus(), 
                order != null);
        
        return event;
    }
    
    /**
     * Link an existing order to events that were created before the order existed.
     * This is used in event-driven architecture when orders are created after events.
     * 
     * @param order The order to link to events
     * @return Number of events linked
     */
    @Transactional
    public int linkOrderToEvents(Order order) {
        if (order.getFixClOrdId() == null) {
            log.warn("Order has no ClOrdID, cannot link to events. OrderId={}", order.getId());
            return 0;
        }
        
        // Find all events for this ClOrdID that don't have an order linked yet
        List<OrderEvent> unlinkedEvents = orderEventRepository.findByFixClOrdIdOrderByEventTimeAsc(order.getFixClOrdId())
                .stream()
                .filter(event -> event.getOrder() == null)
                .toList();
        
        if (unlinkedEvents.isEmpty()) {
            log.debug("No unlinked events found for ClOrdID={}, OrderId={}", order.getFixClOrdId(), order.getId());
            return 0;
        }
        
        // Link events to order
        for (OrderEvent event : unlinkedEvents) {
            event.setOrder(order);
            orderEventRepository.save(event);
        }
        
        // Update order's events collection
        order.getEvents().addAll(unlinkedEvents);
        orderRepository.save(order);
        
        log.info("Linked {} events to order: ClOrdID={}, OrderId={}", 
                unlinkedEvents.size(), order.getFixClOrdId(), order.getId());
        
        return unlinkedEvents.size();
    }


    /**
     * Create a shadow account order and link it to the primary order by ClOrdID.
     * This is used when copying orders to shadow accounts.
     * Simply sets the primaryOrderClOrdId to link the shadow order to its primary order.
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
     * @return Created Order entity, or null if shadow order already exists
     */
    @Transactional
    public Order createShadowAccountOrder(String primaryOrderClOrdId, Account shadowAccount,
                                          String shadowClOrdId, String symbol, Character side,
                                          Character ordType, java.math.BigDecimal orderQty,
                                          java.math.BigDecimal price, java.math.BigDecimal stopPx,
                                          Character timeInForce, String exDestination) {
        // Check if shadow order already exists
        Optional<Order> existingShadowOrderOpt = orderRepository.findByFixClOrdId(shadowClOrdId);
        if (existingShadowOrderOpt.isPresent()) {
            log.debug("Shadow order already exists: ShadowClOrdID={}", shadowClOrdId);
            return existingShadowOrderOpt.get();
        }
        
        // Create shadow order with primaryOrderClOrdId set to link to primary order
        // No need to check if primary order exists - we just store the ClOrdID reference
        Order shadowOrder = Order.builder()
                .fixClOrdId(shadowClOrdId)
                .fixOrderId(null) // Will be set when ExecutionReport is received
                .account(shadowAccount)
                .primaryOrderClOrdId(primaryOrderClOrdId) // Link to primary order by ClOrdID
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
        
        // Save shadow order
        shadowOrder = orderRepository.save(shadowOrder);
        
        log.info("Created shadow account order: ShadowClOrdID={}, ShadowAccount={}, PrimaryClOrdID={}", 
                shadowClOrdId, shadowAccount.getAccountNumber(), primaryOrderClOrdId);
        
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
        
        // Find shadow order by ClOrdID (handle duplicates)
        Order order = findOrderByClOrdId(context.getClOrdID());
        if (order != null) {
            // Order exists - update it
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
            
            // Create shadow order with primaryOrderClOrdId set to link to primary order
            // No need to check if primary order exists - we just store the ClOrdID reference
            order = buildOrderFromContext(context);
            order.setAccount(shadowAccount);
            order.setPrimaryOrderClOrdId(primaryClOrdId); // Link to primary order by ClOrdID
            
            // Save shadow order
            order = orderRepository.save(order);
            log.info("Created copy order from fill ExecutionReport: ClOrdID={}, OrderID={}, Account={}, Symbol={}, PrimaryClOrdID={}", 
                    order.getFixClOrdId(), order.getFixOrderId(), 
                    order.getAccount().getAccountNumber(), order.getSymbol(), primaryClOrdId);
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
     * Find order by ClOrdID, handling potential duplicates.
     * If duplicates exist, returns the most recent one (by created_at).
     * 
     * @param clOrdId The ClOrdID to search for
     * @return The order, or null if not found
     */
    private Order findOrderByClOrdId(String clOrdId) {
        try {
            Optional<Order> orderOpt = orderRepository.findByFixClOrdId(clOrdId);
            if (orderOpt.isPresent()) {
                return orderOpt.get();
            }
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            // Handle duplicate orders - get the most recent one
            log.warn("Duplicate orders found for ClOrdID={}, selecting most recent one. Error: {}", 
                    clOrdId, e.getMessage());
            List<Order> orders = orderRepository.findByFixClOrdIdOrderByCreatedAtDesc(clOrdId);
            if (!orders.isEmpty()) {
                Order selectedOrder = orders.get(0);
                log.info("Selected most recent order for ClOrdID={}: OrderId={}, CreatedAt={}", 
                        clOrdId, selectedOrder.getId(), selectedOrder.getCreatedAt());
                return selectedOrder;
            }
        }
        return null;
    }
    
    /**
     * Find primary order by ClOrdID, handling potential duplicates.
     * Alias for findOrderByClOrdId for clarity.
     * 
     * @param clOrdId The ClOrdID to search for
     * @return The primary order, or null if not found
     */
    private Order findPrimaryOrderByClOrdId(String clOrdId) {
        return findOrderByClOrdId(clOrdId);
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
            
            // Save order
            order = orderRepository.save(order);
            log.info("Created primary order from fill ExecutionReport: ClOrdID={}, OrderID={}, Account={}, Symbol={}", 
                    order.getFixClOrdId(), order.getFixOrderId(), 
                    order.getAccount().getAccountNumber(), order.getSymbol());
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
        try {
            return orderRepository.findByFixClOrdId(clOrdId).isPresent();
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            // Handle duplicates - if any exist, return true
            List<Order> orders = orderRepository.findByFixClOrdIdOrderByCreatedAtDesc(clOrdId);
            return !orders.isEmpty();
        }
    }

    /**
     * Asynchronously create shadow account order.
     * This is called after sending the FIX message, non-blocking.
     * Creates the order with ExecType='0', OrdStatus='0'.
     * Events will be created when ExecutionReports are received.
     * 
     * @param primaryOrderClOrdId The ClOrdID of the primary order
     * @param shadowAccount The shadow account
     * @param shadowClOrdId The ClOrdID for the shadow order
     * @param symbol Order symbol
     * @param side Order side
     * @param ordType Order type
     * @param orderQty Order quantity
     * @param price Order price (nullable)
     * @param stopPx Stop price (nullable)
     * @param timeInForce Time in force
     * @param exDestination Route/destination
     * @param sessionID FIX session ID
     */
    public void createShadowOrderWithStagedEventAsync(String primaryOrderClOrdId, Account shadowAccount,
                                                      String shadowClOrdId, String symbol, Character side,
                                                      Character ordType, java.math.BigDecimal orderQty,
                                                      java.math.BigDecimal price, java.math.BigDecimal stopPx,
                                                      Character timeInForce, String exDestination, SessionID sessionID) {
        orderMirroringExecutor.execute(() -> {
            try {
                createShadowOrder(primaryOrderClOrdId, shadowAccount, shadowClOrdId, symbol, side,
                        ordType, orderQty, price, stopPx, timeInForce, exDestination, sessionID);
            } catch (Exception e) {
                log.error("Error creating shadow order asynchronously: ShadowClOrdID={}, Error={}", 
                        shadowClOrdId, e.getMessage(), e);
            }
        });
    }

    /**
     * Create shadow account order (transactional).
     * This is the actual implementation called by the async method.
     * Simply sets the primaryOrderClOrdId to link the shadow order to its primary order.
     * Events will be created when ExecutionReports are received.
     */
    @Transactional
    private void createShadowOrder(String primaryOrderClOrdId, Account shadowAccount,
                                  String shadowClOrdId, String symbol, Character side,
                                  Character ordType, java.math.BigDecimal orderQty,
                                  java.math.BigDecimal price, java.math.BigDecimal stopPx,
                                  Character timeInForce, String exDestination, SessionID sessionID) {
        // Check if shadow order already exists
        Optional<Order> existingShadowOrderOpt = orderRepository.findByFixClOrdId(shadowClOrdId);
        if (existingShadowOrderOpt.isPresent()) {
            log.debug("Shadow order already exists: ShadowClOrdID={}", shadowClOrdId);
            return;
        }
        
        // Create shadow order with primaryOrderClOrdId set to link to primary order
        // No need to check if primary order exists - we just store the ClOrdID reference
        // This allows shadow orders to be created even if primary order doesn't exist yet
        Order shadowOrder = Order.builder()
                .fixClOrdId(shadowClOrdId)
                .fixOrderId(null) // Will be set when ExecutionReport is received
                .account(shadowAccount)
                .primaryOrderClOrdId(primaryOrderClOrdId) // Link to primary order by ClOrdID
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
        
        // Save shadow order
        shadowOrder = orderRepository.save(shadowOrder);
        
        // Link order to any existing events that were created before the order (event-driven)
        linkOrderToEvents(shadowOrder);
        
        log.info("Created shadow account order: ShadowClOrdID={}, ShadowAccount={}, PrimaryClOrdID={}", 
                shadowClOrdId, shadowAccount.getAccountNumber(), primaryOrderClOrdId);
    }

    /**
     * Create event for a shadow order (event-driven: order not required).
     * Used in NewOrderHandler when broker confirms the shadow order.
     * Creates event with ExecType='0', OrdStatus='0' (New).
     * The order can be created later and linked to this event.
     * 
     * @param context ExecutionReportContext containing order information
     * @param sessionID FIX session ID
     * @return Created OrderEvent
     */
    @Transactional
    public OrderEvent createEventForShadowOrder(ExecutionReportContext context, SessionID sessionID) {
        // Event-driven: create event directly, order is optional
        return createEventFromExecutionReport(context, sessionID);
    }

    /**
     * Create event for any order (primary or shadow) from ExecutionReport (event-driven).
     * The order is optional - events can exist independently.
     * Used in FillOrderHandler when fills arrive.
     * 
     * @param context ExecutionReportContext containing order information
     * @param sessionID FIX session ID
     * @return Created OrderEvent
     */
    @Transactional
    public OrderEvent createEventForOrder(ExecutionReportContext context, SessionID sessionID) {
        // Event-driven: create event directly, order is optional
        return createEventFromExecutionReport(context, sessionID);
    }
}

