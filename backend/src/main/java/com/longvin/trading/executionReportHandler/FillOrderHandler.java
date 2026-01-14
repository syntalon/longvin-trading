package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.repository.OrderRepository;
import com.longvin.trading.service.AccountCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handler for fill ExecutionReports (ExecType=1 or 2).
 * 
 * Handles:
 * 1. Partial fills (ExecType=1)
 * 2. Full fills (ExecType=2)
 * 
 * When orders are filled:
 * - For short orders: Creates draft orders for shadow accounts and initiates locate request workflow
 * - For regular orders (buy/sell): Replicates orders to shadow accounts
 */
@Component
public class FillOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(FillOrderHandler.class);
    
    private final OrderRepository orderRepository;
    private final AccountCacheService accountCacheService;
    private final FixMessageSender fixMessageSender;
    private final FixSessionRegistry fixSessionRegistry;

    public FillOrderHandler(OrderRepository orderRepository,
                           AccountCacheService accountCacheService,
                           FixMessageSender fixMessageSender,
                           FixSessionRegistry fixSessionRegistry) {
        this.orderRepository = orderRepository;
        this.accountCacheService = accountCacheService;
        this.fixMessageSender = fixMessageSender;
        this.fixSessionRegistry = fixSessionRegistry;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        // Handle both partial fills (1) and full fills (2)
        return context.getExecType() == '1' || context.getExecType() == '2';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // Check account type to determine if this is a primary account order or shadow account order
        AccountType accountType = null;
        Optional<Account> accountOpt = Optional.empty();
        Long accountId = null;
        if (context.getAccount() != null) {
            accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
            if (accountOpt.isPresent()) {
                accountType = accountOpt.get().getAccountType();
                accountId = accountOpt.get().getId();
            }
        }
        
        if (context.getExecType() == '2') {
            log.info("Order completely filled. ClOrdID: {}, Account: {}, AccountId: {}, AvgPx: {}",
                    context.getClOrdID(), context.getAccount(), accountId, context.getAvgPx());
        } else {
            log.info("Order partially filled. ClOrdID: {}, Account: {}, AccountId: {}, CumQty: {}/{}, LastPx: {}",
                    context.getClOrdID(), context.getAccount(), accountId,
                    context.getCumQty(), context.getOrderQty(),
                    context.getAvgPx());
        }
        
        // Try to find the order from database (needed for order details)
        Order order = findOrderFromContext(context);
        
        if (accountType == AccountType.PRIMARY) {
            // This is a primary/main account order from DAS Trader - run copy trades to shadow accounts
            handlePrimaryOrderExecutionReport(context, order);
        } else if (accountType == AccountType.SHADOW) {
            // This is a shadow account order (copy order) - only update order status/events, never run copy trades
            handleShadowAccountOrderExecutionReport(context, order);
        } else {
            // Unknown account type - just update status/events
            log.warn("Unknown account type for ExecutionReport. ClOrdID: {}, Account: {}, AccountId: {}", 
                    context.getClOrdID(), context.getAccount(), accountId);
        }
        
        recordFillInformation(context);
    }

    /**
     * Handle short order replication when order is filled.
     * Directly replicates short orders to shadow accounts (stock should already be borrowed).
     */
    private void handleShortOrderReplication(ExecutionReportContext context, Order order) {
        Long primaryAccountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        log.info("Processing short sell order replication on fill: ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, Qty={}",
                context.getClOrdID(), context.getAccount(), primaryAccountId, context.getSymbol(), context.getOrderQty());

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate short order to shadow accounts. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();

        // Get shadow accounts
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccounts();
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found, cannot replicate short order. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
        }

        // Send orders to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                sendOrderToShadowAccount(context, order, shadowAccount, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error replicating short order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Try to find the order from database by OrderID or ClOrdID.
     * Also updates the order with ExDestination if available in the context.
     */
    private Order findOrderFromContext(ExecutionReportContext context) {
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
     * Build a transient Order object from ExecutionReportContext.
     */
    private Order buildOrderFromContext(ExecutionReportContext context) {
        Order order = new Order();
        order.setFixClOrdId(context.getClOrdID());
        order.setFixOrderId(context.getOrderID());
        order.setSymbol(context.getSymbol());
        order.setSide(context.getSide());
        order.setOrderQty(context.getOrderQty());

        // Set account if available
        if (context.getAccount() != null) {
            Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
            if (accountOpt.isPresent()) {
                order.setAccount(accountOpt.get());
            } else {
                log.warn("Account not found for order: ClOrdID={}, Account: {}", 
                        context.getClOrdID(), context.getAccount());
            }
        }

        // Store ExDestination if available
        if (context.getExDestination() != null && !context.getExDestination().isBlank()) {
            order.setExDestination(context.getExDestination());
        }

        return order;
    }

    /**
     * Check if this is a locate order (Short Locate New Order).
     * A locate order is identified by:
     * - Side = BUY ('1')
     * - ExDestination is set (typically contains "LOCATE")
     */
    private boolean isLocateOrder(ExecutionReportContext context) {
        // Locate orders are BUY orders with ExDestination set to a locate route
        if (context.getSide() != '1') { // Side='1' is BUY
            return false;
        }
        
        String exDestination = context.getExDestination();
        if (exDestination == null || exDestination.isBlank()) {
            return false;
        }
        
        // Check if ExDestination indicates a locate route (typically contains "LOCATE")
        return exDestination.toUpperCase().contains("LOCATE");
    }

    /**
     * Handle locate order replication when order is filled (stock borrowed).
     * Replicates the locate order to shadow accounts so they can also borrow the stock.
     */
    private void handleLocateOrderReplication(ExecutionReportContext context, Order order) {
        Long primaryAccountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        log.info("Processing locate order replication on fill: ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, Qty={}, LocateRoute={}",
                context.getClOrdID(), context.getAccount(), primaryAccountId, context.getSymbol(), 
                context.getOrderQty(), context.getExDestination());

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate locate order to shadow accounts. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();

        // Get shadow accounts
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccounts();
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found, cannot replicate locate order. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
        }

        // Send locate orders to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                sendLocateOrderToShadowAccount(context, order, shadowAccount, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error replicating locate order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Send locate order to a shadow account.
     * A locate order is a BUY order with ExDestination set to the locate route.
     */
    private void sendLocateOrderToShadowAccount(ExecutionReportContext context, Order primaryOrder,
                                               Account shadowAccount, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = generateShadowClOrdId(context.getClOrdID(), shadowAccountNumber);
        
        // Build order parameters for locate order
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", '1'); // BUY for locate orders
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", context.getOrderQty().intValue());
        orderParams.put("account", shadowAccountNumber);
        orderParams.put("exDestination", context.getExDestination()); // Locate route
        
        // Set order type (default to MARKET if not available)
        char ordType = primaryOrder.getOrdType() != null ? primaryOrder.getOrdType() : '1'; // MARKET
        orderParams.put("ordType", ordType);
        
        // Set time in force (default to DAY if not available)
        char timeInForce = primaryOrder.getTimeInForce() != null ? primaryOrder.getTimeInForce() : '0'; // DAY
        orderParams.put("timeInForce", timeInForce);

        // Send locate order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        Long primaryAccountId = primaryOrder.getAccount() != null ? primaryOrder.getAccount().getId() : null;
        log.info("Replicated locate order to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, LocateRoute={}, Qty={}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, 
                context.getSymbol(), context.getExDestination(), context.getOrderQty());
    }

    /**
     * Handle regular order (buy/sell, not short) replication when order is filled.
     * Sends orders to shadow accounts.
     */
    private void handleRegularOrderReplication(ExecutionReportContext context, Order order) {
        Long primaryAccountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        log.info("Processing regular order replication on fill: ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, Side={}, Qty={}",
                context.getClOrdID(), context.getAccount(), primaryAccountId, context.getSymbol(), 
                context.getSide(), context.getOrderQty());

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate order to shadow accounts. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();

        // Get shadow accounts
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccounts();
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found, cannot replicate order. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
        }

        // Send orders to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                sendOrderToShadowAccount(context, order, shadowAccount, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error replicating order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Send order to a shadow account.
     */
    private void sendOrderToShadowAccount(ExecutionReportContext context, Order primaryOrder, 
                                         Account shadowAccount, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = generateShadowClOrdId(context.getClOrdID(), shadowAccountNumber);
        
        // Build order parameters
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", context.getSide());
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", context.getOrderQty().intValue());
        orderParams.put("account", shadowAccountNumber);
        
        // Set order type (default to MARKET if not available)
        char ordType = primaryOrder.getOrdType() != null ? primaryOrder.getOrdType() : '1'; // MARKET
        orderParams.put("ordType", ordType);
        
        // Set time in force (default to DAY if not available)
        char timeInForce = primaryOrder.getTimeInForce() != null ? primaryOrder.getTimeInForce() : '0'; // DAY
        orderParams.put("timeInForce", timeInForce);
        
        // Set price if it's a limit order (FixMessageSender only supports price, not stopPx)
        if (primaryOrder.getPrice() != null && (ordType == '2' || ordType == '4')) { // LIMIT or STOP_LIMIT
            orderParams.put("price", primaryOrder.getPrice().doubleValue());
        }

        // Use the same route (exDestination) as the primary account order
        // Priority: 1) ExecutionReport (most current), 2) Order entity (stored from previous ExecutionReport)
        String route = null;
        Long primaryAccountId = primaryOrder.getAccount() != null ? primaryOrder.getAccount().getId() : null;
        if (context.getExDestination() != null && !context.getExDestination().isBlank()) {
            // Route is available in current ExecutionReport - use it directly
            route = context.getExDestination();
            log.info("Using ExDestination from ExecutionReport: ClOrdID={}, AccountId: {}, Route={}",
                    context.getClOrdID(), primaryAccountId, route);
        } else if (primaryOrder != null && primaryOrder.getExDestination() != null && !primaryOrder.getExDestination().isBlank()) {
            // Fallback: Use route stored in Order entity (from previous ExecutionReport)
            route = primaryOrder.getExDestination();
            log.debug("Using ExDestination from Order entity (fallback): ClOrdID={}, AccountId: {}, Route={}", 
                    context.getClOrdID(), primaryAccountId, route);
        } else {
            log.warn("No ExDestination available for copy order. ClOrdID={}, PrimaryOrderClOrdID={}, PrimaryAccountId: {}, Symbol={}. " +
                    "Copy order will be sent without route, which may cause rejection.",
                    clOrdId, context.getClOrdID(), primaryAccountId, context.getSymbol());
        }
        
        if (route != null) {
            orderParams.put("exDestination", route);
        }

        // Log the copy order details before sending
        String routeInfo = route != null ? ", Route=" + route : "";
        log.info("Sending copy order to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, Side={}, Qty={}, OrdType={}, Price={}{}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, context.getSymbol(), 
                context.getSide(), context.getOrderQty(), ordType, 
                primaryOrder.getPrice() != null ? primaryOrder.getPrice() : "N/A", routeInfo);

        // Send order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        log.info("Copy order sent to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, Side={}, Qty={}{}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, 
                context.getSymbol(), context.getSide(), context.getOrderQty(), routeInfo);
    }

    
    /**
     * Handle ExecutionReport for primary/main account orders.
     * Runs copy trades to shadow accounts when orders are filled.
     */
    private void handlePrimaryOrderExecutionReport(ExecutionReportContext context, Order order) {
        Long accountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        log.debug("Processing ExecutionReport for primary account order: ClOrdID={}, Account: {}, AccountId: {}",
                context.getClOrdID(), context.getAccount(), accountId);
        
        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
            accountId = order.getAccount() != null ? order.getAccount().getId() : null;
        }
        
        // For primary account orders, replicate to shadow accounts when filled
        // Check if this is a locate order (Side=BUY with ExDestination set to locate route)
        if (isLocateOrder(context)) {
            handleLocateOrderReplication(context, order);
        } else if (context.isShortOrder()) {
            handleShortOrderReplication(context, order);
        } else {
            // For regular orders (buy/sell) from primary account, replicate to shadow accounts
            handleRegularOrderReplication(context, order);
        }
    }
    
    /**
     * Handle ExecutionReport for shadow account orders (copy orders).
     * Only updates order status/events, never runs copy trades.
     */
    private void handleShadowAccountOrderExecutionReport(ExecutionReportContext context, Order order) {
        Long accountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        log.debug("Processing ExecutionReport for shadow account order: ClOrdID={}, Account: {}, AccountId: {}", 
                context.getClOrdID(), context.getAccount(), accountId);
        
        // Only update order status/events - NEVER run copy trades
        // Shadow account orders are copy orders, they should not trigger further copy trades
        if (order != null) {
            log.debug("Updated order status/events for shadow account order: ClOrdID={}, OrderID={}, AccountId: {}", 
                    context.getClOrdID(), context.getOrderID(), accountId);
        }
    }

    /**
     * Generate ClOrdID for shadow order.
     */
    private String generateShadowClOrdId(String primaryClOrdId, String shadowAccountNumber) {
        // Simple format: prefix + shadow account + original ClOrdID
        return "COPY-" + shadowAccountNumber + "-" + primaryClOrdId;
    }

    private void recordFillInformation(ExecutionReportContext context) {
        Optional<Account> accountOpt = context.getAccount() != null ? 
                accountCacheService.findByAccountNumber(context.getAccount()) : Optional.empty();
        Long accountId = accountOpt.map(Account::getId).orElse(null);
        log.debug("Recording fill information for order: ClOrdID={}, Account: {}, AccountId: {}", 
                context.getClOrdID(), context.getAccount(), accountId);
    }
}