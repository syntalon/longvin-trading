package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.repository.OrderRepository;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.LocateRouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private final LocateRouteService locateRouteService;

    public FillOrderHandler(OrderRepository orderRepository,
                           AccountCacheService accountCacheService,
                           FixMessageSender fixMessageSender,
                           FixSessionRegistry fixSessionRegistry,
                           LocateRouteService locateRouteService) {
        this.orderRepository = orderRepository;
        this.accountCacheService = accountCacheService;
        this.fixMessageSender = fixMessageSender;
        this.fixSessionRegistry = fixSessionRegistry;
        this.locateRouteService = locateRouteService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        // Handle both partial fills (1) and full fills (2)
        // BUT exclude OrdStatus='B' (Calculated/Locate Offer) - those are handled by LocateOfferHandler
        // OrdStatus='B' is used for locate offers in Type 1 routes, not actual fills
        if (context.getOrdStatus() == 'B') {
            return false; // Let LocateOfferHandler handle locate offers
        }
        return context.getExecType() == '1' || context.getExecType() == '2';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // First check if this is a copy order by ClOrdID prefix (most reliable check)
        // Copy orders have ClOrdID format: "COPY-{shadowAccount}-{primaryClOrdID}"
        boolean isCopyOrder = context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-");
        
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
        
        // Try to find the order from database (needed for order details)
        Order order = findOrderFromContext(context);
        
        // Additional check: if order exists in DB and belongs to shadow account, it's a copy order
        if (!isCopyOrder && order != null && order.getAccount() != null) {
            if (order.getAccount().getAccountType() == AccountType.SHADOW) {
                isCopyOrder = true;
                log.debug("Detected copy order from database: ClOrdID={}, AccountType=SHADOW", context.getClOrdID());
            }
        }
        
        // CRITICAL: Also check if the order's ClOrdID in DB starts with "COPY-" 
        // This handles cases where ExecutionReport comes back with OrderID as ClOrdID
        // (DAS Trader sometimes uses OrderID as ClOrdID in subsequent ExecutionReports)
        if (!isCopyOrder && order != null && order.getFixClOrdId() != null) {
            if (order.getFixClOrdId().startsWith("COPY-")) {
                isCopyOrder = true;
                log.info("Detected copy order from database ClOrdID: ExecutionReport ClOrdID={}, DB ClOrdID={}", 
                        context.getClOrdID(), order.getFixClOrdId());
            }
        }
        
        if (context.getExecType() == '2') {
            log.info("Order completely filled. ClOrdID: {}, Account: {}, AccountId: {}, AvgPx: {}, IsCopyOrder: {}",
                    context.getClOrdID(), context.getAccount(), accountId, context.getAvgPx(), isCopyOrder);
        } else {
            log.info("Order partially filled. ClOrdID: {}, Account: {}, AccountId: {}, CumQty: {}/{}, LastPx: {}, IsCopyOrder: {}",
                    context.getClOrdID(), context.getAccount(), accountId,
                    context.getCumQty(), context.getOrderQty(),
                    context.getAvgPx(), isCopyOrder);
        }
        
        if (isCopyOrder) {
            // This is a copy order - only update order status/events, never run copy trades
            // Even if the ExecutionReport has a PRIMARY account, if ClOrdID starts with "COPY-", it's a copy order
            // Note: A locate order is a BUY order with ExDestination set to locate route. When it fills,
            // it appears as a filled BUY order, which is correct behavior.
            String orderType = isLocateOrder(context) ? "locate order (BUY with locate route)" : "regular order";
            log.info("Copy order detected - skipping replication. ClOrdID={}, Account={}, AccountId={}, ExecType={}, OrdStatus={}, OrderType={}, Route={}",
                    context.getClOrdID(), context.getAccount(), accountId, context.getExecType(), 
                    context.getOrdStatus(), orderType, context.getExDestination());
            handleShadowAccountOrderExecutionReport(context, order);
        } else if (accountType == AccountType.PRIMARY) {
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

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
            primaryAccountId = order.getAccount() != null ? order.getAccount().getId() : null;
        }

        // Get primary account to filter shadow accounts by strategy_key
        Account primaryAccount = order.getAccount();
        if (primaryAccount == null) {
            log.warn("Primary account not found in order, cannot replicate short order. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }

        // Get shadow accounts with same strategy_key as primary account
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccountsByStrategyKey(primaryAccount);
        log.info("Found {} shadow account(s) for short order replication: StrategyKey={}, PrimaryAccount={}, PrimaryAccountId={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getStrategyKey(), primaryAccount.getAccountNumber(), primaryAccountId,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found with same strategy_key ({}), cannot replicate short order. ClOrdID={}, AccountId: {}",
                    primaryAccount.getStrategyKey(), context.getClOrdID(), primaryAccountId);
            return;
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
     * Check if this is a stop order (stop market or stop limit).
     * Stop market orders have OrdType=3 (STOP/Stop Loss).
     * Stop limit orders have OrdType=4 (STOP_LIMIT).
     */
    private boolean isStopOrder(Order order) {
        if (order == null) {
            return false;
        }
        Character ordType = order.getOrdType();
        // OrdType='3' is STOP (stop market), OrdType='4' is STOP_LIMIT
        return ordType != null && (ordType == '3' || ordType == '4');
    }

    /**
     * Check if this is a locate order (Short Locate New Order).
     * A locate order is identified by:
     * - Side=BUY (1)
     * - ExDestination is a locate route (configured in LocateRouteService)
     * 
     * Note: ClOrdID prefix "LOC-" is also a valid indicator, but the above criteria
     * are more reliable as they match the actual order characteristics.
     */
    private boolean isLocateOrder(ExecutionReportContext context) {
        // Check if Side is BUY
        if (context.getSide() != '1') { // '1' = BUY
            return false;
        }
        
        // Check if ExDestination is a locate route
        String exDestination = context.getExDestination();
        if (exDestination != null && !exDestination.isBlank()) {
            if (locateRouteService.isLocateRoute(exDestination)) {
                return true;
            }
        }
        
        // Fallback: Check ClOrdID prefix (for backward compatibility)
        String clOrdId = context.getClOrdID();
        if (clOrdId != null && clOrdId.startsWith("LOC-")) {
            return true;
        }
        
        return false;
    }

    /**
     * Handle locate order replication when order is filled (stock borrowed).
     * 
     * Two different processes based on route type:
     * 
     * Type 0 (e.g., TESTSL):
     * 1. User places locate order in DAS Trader → ExecutionReport with fill (section 3.5)
     * 2. Check route type is Type 0 and filled status
     * 3. Send locate order (section 3.14) directly to shadow accounts (skip quote request/response)
     * 4. Get ExecutionReport (section 3.5) → Route 0 locate copy completed
     * 
     * Type 1 (e.g., LOCATE):
     * 1. User places locate order in DAS Trader → ExecutionReport with fill (section 3.5)
     * 2. Check route type is Type 1
     * 3. Send locate order (section 3.14) directly for each shadow account
     * 4. Get ExecutionReport (section 3.5) with tag 39=B → LocateOfferHandler handles it
     * 5. LocateOfferHandler sends accept/reject offer (section 3.13)
     * 6. Get ExecutionReport (section 3.5) again → Route 1 locate copy completed
     */
    private void handleLocateOrderReplication(ExecutionReportContext context, Order order) {
        Long primaryAccountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        String locateRoute = context.getExDestination();
        
        // Get route type using LocateRouteService
        Optional<LocateRouteService.LocateRouteInfo> routeInfoOpt = locateRouteService.getLocateRouteInfo(locateRoute);
        if (routeInfoOpt.isEmpty()) {
            log.warn("Route {} is not a locate route, cannot process locate replication. ClOrdID={}, AccountId: {}",
                    locateRoute, context.getClOrdID(), primaryAccountId);
            return;
        }
        
        LocateRouteService.LocateRouteInfo routeInfo = routeInfoOpt.get();
        LocateRouteService.LocateRouteType routeType = routeInfo.getType();
        
        log.info("Processing locate order replication on fill: ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, Qty={}, LocateRoute={}, RouteType={}",
                context.getClOrdID(), context.getAccount(), primaryAccountId, context.getSymbol(), 
                context.getOrderQty(), locateRoute, routeType);

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate locate order to shadow accounts. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
            primaryAccountId = order.getAccount() != null ? order.getAccount().getId() : null;
        }

        // Get primary account to filter shadow accounts by strategy_key
        Account primaryAccount = order.getAccount();
        if (primaryAccount == null) {
            log.warn("Primary account not found in order, cannot replicate locate order. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }

        // Get shadow accounts with same strategy_key as primary account
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccountsByStrategyKey(primaryAccount);
        log.info("Found {} shadow account(s) for locate order replication: StrategyKey={}, PrimaryAccount={}, PrimaryAccountId={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getStrategyKey(), primaryAccount.getAccountNumber(), primaryAccountId,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found with same strategy_key ({}), cannot replicate locate order. ClOrdID={}, AccountId: {}",
                    primaryAccount.getStrategyKey(), context.getClOrdID(), primaryAccountId);
            return;
        }

        // Process based on route type
        // Type 0: Send Short Locate Quote Request (3.11) → Receive Quote Response (3.12) → Send locate order (3.14)
        // Type 1: Send locate order (3.14) directly → Returns offer (OrdStatus=B) → Accept/reject → Fills
        if (routeType == LocateRouteService.LocateRouteType.TYPE_0) {
            // Type 0: Send Short Locate Quote Request (3.11) for each shadow account
            // LocateResponseHandler will receive the response (3.12) and send the locate order (3.14)
            log.info("Type 0 route detected, sending Short Locate Quote Request (3.11) for each shadow account");
            for (Account shadowAccount : shadowAccounts) {
                try {
                    sendShortLocateQuoteRequestForShadowAccount(context, order, shadowAccount, initiatorSessionID);
                } catch (Exception e) {
                    log.error("Error sending Short Locate Quote Request to shadow account {} (AccountId: {}): {}", 
                            shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
                }
            }
        } else if (routeType == LocateRouteService.LocateRouteType.TYPE_1) {
            // Type 1: Send locate order (section 3.14) directly for each shadow account
            log.info("Type 1 route detected, sending locate order (3.14) directly for each shadow account");
            for (Account shadowAccount : shadowAccounts) {
                try {
                    sendLocateOrderToShadowAccount(context, order, shadowAccount, initiatorSessionID);
                } catch (Exception e) {
                    log.error("Error sending locate order to shadow account {} (AccountId: {}): {}", 
                            shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
                }
            }
        } else {
            log.warn("Unknown route type: {} for route: {}, cannot process locate replication", routeType, locateRoute);
        }
    }

    /**
     * Send Short Locate Quote Request (MsgType=R, section 3.11) to a shadow account.
     * This is used for Type 0 routes where we need to request a quote before sending the locate order.
     * 
     * QuoteReqID format: QL_{shadowAccount}_{primaryClOrdId}_{route}_{timestamp}
     * This format allows LocateResponseHandler to identify which shadow account to send the locate order to.
     */
    private void sendShortLocateQuoteRequestForShadowAccount(ExecutionReportContext context, Order primaryOrder,
                                                             Account shadowAccount, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String locateRoute = context.getExDestination();
        String primaryClOrdId = context.getClOrdID();
        
        // Generate QuoteReqID in format: QL_{shadowAccount}_{primaryClOrdId}_{route}_{timestamp}
        // This allows LocateResponseHandler to identify the shadow account and route
        long timestamp = System.currentTimeMillis();
        String quoteReqID = String.format("QL_%s_%s_%s_%d", shadowAccountNumber, primaryClOrdId, locateRoute, timestamp);
        
        int qty = context.getOrderQty().intValue();
        
        log.info("Sending Short Locate Quote Request (3.11) to shadow account {} (AccountId: {}): " +
                "QuoteReqID={}, Symbol={}, Qty={}, Route={}, PrimaryClOrdID={}",
                shadowAccountNumber, shadowAccount.getId(), quoteReqID, context.getSymbol(), qty, locateRoute, primaryClOrdId);
        
        fixMessageSender.sendShortLocateQuoteRequest(
                initiatorSessionID,
                context.getSymbol(),
                qty,
                shadowAccountNumber,
                locateRoute,
                quoteReqID
        );
        
        Long primaryAccountId = primaryOrder != null && primaryOrder.getAccount() != null ? 
                primaryOrder.getAccount().getId() : null;
        log.info("Short Locate Quote Request (3.11) sent to shadow account {} (AccountId: {}): " +
                "QuoteReqID={}, Symbol={}, Qty={}, Route={}, PrimaryAccountId: {}",
                shadowAccountNumber, shadowAccount.getId(), quoteReqID, context.getSymbol(), qty, locateRoute, primaryAccountId);
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
        
        // Set time in force - use from context first, then order entity, then default to DAY
        // Parent order might have TimeInForce=5 (Day+), we should copy it to maintain same behavior
        Character timeInForce = context.getTimeInForce();
        if (timeInForce == null) {
            timeInForce = primaryOrder.getTimeInForce() != null ? primaryOrder.getTimeInForce() : '0'; // Default to DAY
        }
        orderParams.put("timeInForce", timeInForce);

        // Send locate order
        // IMPORTANT: This is a locate-only order (borrow stock), not an execute buy order.
        // The ExDestination field set to a locate route (e.g., "TESTSL", "LOCATE") tells DAS
        // this is a locate order, not a regular buy order. When it "fills" (OrdStatus=2),
        // it means stock has been located/borrowed, NOT that a buy was executed.
        String locateRoute = context.getExDestination();
        log.info("Sending locate-only order (borrow stock, not execute buy) to shadow account {} (AccountId: {}): " +
                "ClOrdID={}, Symbol={}, Side=BUY(1), ExDestination={} (locate route), Qty={}, OrdType={}, TimeInForce={}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, context.getSymbol(), 
                locateRoute, context.getOrderQty(), ordType, timeInForce);
        
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        Long primaryAccountId = primaryOrder != null && primaryOrder.getAccount() != null ? 
                primaryOrder.getAccount().getId() : null;
        log.info("Locate order (3.14) sent to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, LocateRoute={}, Qty={}. " +
                "This is a locate-only order (borrow stock), not an execute buy order.",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, 
                context.getSymbol(), locateRoute, context.getOrderQty());
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

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
            primaryAccountId = order.getAccount() != null ? order.getAccount().getId() : null;
        }

        // Get primary account to filter shadow accounts by strategy_key
        Account primaryAccount = order.getAccount();
        if (primaryAccount == null) {
            log.warn("Primary account not found in order, cannot replicate order. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccountId);
            return;
        }

        // Get shadow accounts with same strategy_key as primary account
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccountsByStrategyKey(primaryAccount);
        log.info("Found {} shadow account(s) for replication: StrategyKey={}, PrimaryAccount={}, PrimaryAccountId={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getStrategyKey(), primaryAccount.getAccountNumber(), primaryAccountId,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found with same strategy_key ({}), cannot replicate order. ClOrdID={}, AccountId: {}",
                    primaryAccount.getStrategyKey(), context.getClOrdID(), primaryAccountId);
            return;
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
        
        // Set time in force - use from context first, then order entity, then default to DAY
        // Parent order might have TimeInForce=5 (Day+), we should copy it to maintain same behavior
        Character timeInForce = context.getTimeInForce();
        if (timeInForce == null) {
            timeInForce = primaryOrder.getTimeInForce() != null ? primaryOrder.getTimeInForce() : '0'; // Default to DAY
        }
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
        log.info("Sending copy order to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, Side={}, Qty={}, OrdType={}, Price={}{}, AccountInOrder={}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, context.getSymbol(), 
                context.getSide(), context.getOrderQty(), ordType, 
                primaryOrder.getPrice() != null ? primaryOrder.getPrice() : "N/A", routeInfo, shadowAccountNumber);

        // Send order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        log.info("Copy order sent to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, Side={}, Qty={}{}, AccountInOrder={}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, 
                context.getSymbol(), context.getSide(), context.getOrderQty(), routeInfo, shadowAccountNumber);
    }

    
    /**
     * Handle ExecutionReport for primary/main account orders.
     * Runs copy trades to shadow accounts when orders are filled.
     */
    private void handlePrimaryOrderExecutionReport(ExecutionReportContext context, Order order) {
        // CRITICAL SAFEGUARD: Double-check this is NOT a copy order before replicating
        // Check ClOrdID in context
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.warn("BLOCKED: Attempted to replicate copy order! ClOrdID={}, Account={}. This should never happen.",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        // Check ClOrdID in order entity if available
        if (order != null && order.getFixClOrdId() != null && order.getFixClOrdId().startsWith("COPY-")) {
            log.warn("BLOCKED: Attempted to replicate copy order! ExecutionReport ClOrdID={}, DB ClOrdID={}, Account={}. This should never happen.",
                    context.getClOrdID(), order.getFixClOrdId(), context.getAccount());
            return;
        }
        
        Long accountId = order != null && order.getAccount() != null ? order.getAccount().getId() : null;
        log.debug("Processing ExecutionReport for primary account order: ClOrdID={}, Account: {}, AccountId: {}",
                context.getClOrdID(), context.getAccount(), accountId);
        
        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
            accountId = order.getAccount() != null ? order.getAccount().getId() : null;
        }
        
        // Final safeguard: Check account type one more time
        if (order.getAccount() != null && order.getAccount().getAccountType() == AccountType.SHADOW) {
            log.warn("BLOCKED: Attempted to replicate shadow account order! ClOrdID={}, Account={}, AccountType=SHADOW. This should never happen.",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        // For primary account orders, replicate to shadow accounts when filled
        // Skip stop orders (stop market and stop limit) - they are handled in NewOrderHandler when confirmed
        if (isStopOrder(order)) {
            String ordTypeName = order.getOrdType() != null && order.getOrdType() == '3' ? "STOP(3)" : "STOP_LIMIT(4)";
            log.info("Stop order detected - skipping replication in FillOrderHandler. " +
                    "ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, OrdType={}. " +
                    "Stop orders are copied when confirmed (NewOrderHandler), not when filled.",
                    context.getClOrdID(), context.getAccount(), accountId, context.getSymbol(), ordTypeName);
            // Only update order status/events, do NOT replicate stop orders
            return;
        }
        
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