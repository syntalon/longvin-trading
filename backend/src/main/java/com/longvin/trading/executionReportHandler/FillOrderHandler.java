package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.entities.accounts.Route;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.CopyRuleService;
import com.longvin.trading.service.OrderService;
import com.longvin.trading.service.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.math.BigDecimal;
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

    private final AccountCacheService accountCacheService;
    private final FixMessageSender fixMessageSender;
    private final FixSessionRegistry fixSessionRegistry;
    private final RouteService routeService;
    private final CopyRuleService copyRuleService;
    private final OrderService orderService;

    public FillOrderHandler(AccountCacheService accountCacheService,
                           FixMessageSender fixMessageSender,
                           FixSessionRegistry fixSessionRegistry,
                           RouteService routeService,
                           CopyRuleService copyRuleService,
                           OrderService orderService) {
        this.accountCacheService = accountCacheService;
        this.fixMessageSender = fixMessageSender;
        this.fixSessionRegistry = fixSessionRegistry;
        this.routeService = routeService;
        this.copyRuleService = copyRuleService;
        this.orderService = orderService;
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
        // Determine if this is a copy order based on ExecutionReport details only
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
                
                // If account type is SHADOW, it's a copy order (handles cases where ExecutionReport 
                // has OrderID as ClOrdID instead of original "COPY-..." ClOrdID)
                if (!isCopyOrder && accountType == AccountType.SHADOW) {
                    isCopyOrder = true;
                    log.info("Detected copy order from ExecutionReport account type: ClOrdID={}, Account={}, AccountType=SHADOW. " +
                            "This handles cases where ExecutionReport has OrderID as ClOrdID instead of original COPY- prefix.",
                            context.getClOrdID(), context.getAccount());
                }
            }
        }

        // Update ExDestination in order entity if order exists (for reference, not for copy order detection)
        orderService.findOrderFromContext(context);
        
        if (context.getExecType() == '2') {
            log.info("Order completely filled. ClOrdID: {}, Account: {}, AccountId: {}, AvgPx: {}, IsCopyOrder: {}",
                    context.getClOrdID(), context.getAccount(), accountId, context.getAvgPx(), isCopyOrder);
        } else {
            log.info("Order partially filled. ClOrdID: {}, Account: {}, AccountId: {}, CumQty: {}/{}, LastPx: {}, IsCopyOrder: {}",
                    context.getClOrdID(), context.getAccount(), accountId,
                    context.getCumQty(), context.getOrderQty(),
                    context.getAvgPx(), isCopyOrder);
        }
        
        if (isCopyOrder || accountType == AccountType.SHADOW) {
            // This is a copy order (shadow account) - create event only, never run copy trades
            String orderType = isLocateOrder(context) ? "locate order (BUY with locate route)" : "regular order";
            log.info("Copy order detected - creating event. ClOrdID={}, Account={}, AccountId={}, ExecType={}, OrdStatus={}, OrderType={}, Route={}",
                    context.getClOrdID(), context.getAccount(), accountId, context.getExecType(), 
                    context.getOrdStatus(), orderType, context.getExDestination());
            try {
                orderService.createEventForShadowOrder(context, sessionID);
            } catch (Exception e) {
                log.error("Error creating event for copy order: ClOrdID={}, Account={}, Error={}", 
                        context.getClOrdID(), context.getAccount(), e.getMessage(), e);
            }
        } else if (accountType == AccountType.PRIMARY) {
            // This is a primary/main account order from DAS Trader - create event and run copy trades to shadow accounts
            try {
                // For locate orders, they may not receive a "New" ExecutionReport (ExecType=0, OrdStatus=0)
                // Type 1 routes go directly to OrdStatus='B' (offer), Type 0 routes may skip "New" status
                // So we need to create the order here if it doesn't exist
                if (isLocateOrder(context)) {
                    // Create or update the primary locate order and create event
                    orderService.createOrUpdateOrderForPrimaryAccount(context, sessionID);
                } else {
                    // For regular orders, they should already exist from NewOrderHandler
                    // Just create event (filled/partially filled)
                    orderService.createEventForOrder(context, sessionID);
                }
            } catch (Exception e) {
                log.error("Error creating/updating order or event for primary order: ClOrdID={}, Account={}, Error={}", 
                        context.getClOrdID(), context.getAccount(), e.getMessage(), e);
            }
            // Run copy trades to shadow accounts
            handlePrimaryOrderExecutionReport(context);
        } else {
            // Unknown account type - just create event if order exists
            log.warn("Unknown account type for ExecutionReport. ClOrdID: {}, Account: {}, AccountId: {}", 
                    context.getClOrdID(), context.getAccount(), accountId);
            try {
                orderService.createEventForOrder(context, sessionID);
            } catch (Exception e) {
                log.error("Error creating event for order: ClOrdID={}, Account={}, Error={}", 
                        context.getClOrdID(), context.getAccount(), e.getMessage(), e);
            }
        }
    }

    /**
     * Check if this is a locate order (Short Locate New Order).
     * A locate order is identified by:
     * - Side=BUY (1)
     * - ExDestination is a locate route (has routeType set in routes table)
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
            if (routeService.isLocateRoute(exDestination)) {
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
    private void handleLocateOrderReplication(ExecutionReportContext context, Account primaryAccount) {
        String locateRoute = context.getExDestination();
        
        // Get route type using RouteService
        Optional<Route.LocateRouteType> routeTypeOpt = routeService.getRouteType(locateRoute);
        if (routeTypeOpt.isEmpty()) {
            log.warn("Route {} is not a locate route, cannot process locate replication. ClOrdID={}, AccountId: {}",
                    locateRoute, context.getClOrdID(), primaryAccount.getId());
            return;
        }
        
        Route.LocateRouteType routeType = routeTypeOpt.get();
        
        log.info("Processing locate order replication on fill: ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, Qty={}, LocateRoute={}, RouteType={}",
                context.getClOrdID(), context.getAccount(), primaryAccount.getId(), context.getSymbol(), 
                context.getOrderQty(), locateRoute, routeType);

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate locate order to shadow accounts. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccount.getId());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();

        // Get shadow accounts that match copy rules
        Character ordType = context.getOrdType() != null ? context.getOrdType() : '1'; // Default to MARKET
        String symbol = context.getSymbol();
        BigDecimal quantity = context.getOrderQty();
        
        List<Account> shadowAccounts = copyRuleService.getShadowAccountsForCopy(
                primaryAccount, ordType, symbol, quantity);
        
        log.info("Found {} shadow account(s) for locate order replication via copy rules: PrimaryAccount={}, PrimaryAccountId={}, OrdType={}, Symbol={}, Qty={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getAccountNumber(), primaryAccount.getId(), ordType, symbol, quantity,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found matching copy rules, cannot replicate locate order. ClOrdID={}, AccountId: {}, OrdType={}, Symbol={}, Qty={}",
                    context.getClOrdID(), primaryAccount.getId(), ordType, symbol, quantity);
            return;
        }

        // Process based on route type
        // Type 0: Send Short Locate Quote Request (3.11) → Receive Quote Response (3.12) → Send locate order (3.14)
        // Type 1: Send locate order (3.14) directly → Returns offer (OrdStatus=B) → Accept/reject → Fills
        if (routeType == Route.LocateRouteType.TYPE_0) {
            // Type 0: Send Short Locate Quote Request (3.11) for each shadow account
            // LocateResponseHandler will receive the response (3.12) and send the locate order (3.14)
            log.info("Type 0 route detected, sending Short Locate Quote Request (3.11) for each shadow account");
            for (Account shadowAccount : shadowAccounts) {
                try {
                    // Get copy rule for this account pair to get the locate route
                    Optional<CopyRule> ruleOpt = copyRuleService.getRuleForAccountPair(
                            primaryAccount, shadowAccount, ordType, symbol, quantity);
                    
                    String originalRoute = context.getExDestination();
                    String targetLocateRoute = copyRuleService.getLocateRoute(
                            ruleOpt.orElse(null), originalRoute);
                    
                    sendShortLocateQuoteRequestForShadowAccount(context, primaryAccount, shadowAccount, targetLocateRoute, initiatorSessionID);
                } catch (Exception e) {
                    log.error("Error sending Short Locate Quote Request to shadow account {} (AccountId: {}): {}", 
                            shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
                }
            }
        } else if (routeType == Route.LocateRouteType.TYPE_1) {
            // Type 1: Send locate order (section 3.14) directly for each shadow account
            log.info("Type 1 route detected, sending locate order (3.14) directly for each shadow account");
            for (Account shadowAccount : shadowAccounts) {
                try {
                    // Get copy rule for this account pair
                    Optional<CopyRule> ruleOpt = copyRuleService.getRuleForAccountPair(
                            primaryAccount, shadowAccount, ordType, symbol, quantity);
                    
                    if (ruleOpt.isPresent()) {
                        CopyRule rule = ruleOpt.get();
                        sendLocateOrderToShadowAccountWithRule(context, primaryAccount, shadowAccount, rule, initiatorSessionID);
                    } else {
                        // No copy rule found - skip replication
                        log.error("No copy rule found for account pair, cannot replicate locate order. PrimaryAccount={}, ShadowAccount={}",
                                primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber());
                    }
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
    private void sendShortLocateQuoteRequestForShadowAccount(ExecutionReportContext context, Account primaryAccount,
                                                             Account shadowAccount, String locateRoute, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
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
        
        log.info("Short Locate Quote Request (3.11) sent to shadow account {} (AccountId: {}): " +
                "QuoteReqID={}, Symbol={}, Qty={}, Route={}, PrimaryAccountId: {}",
                shadowAccountNumber, shadowAccount.getId(), quoteReqID, context.getSymbol(), qty, locateRoute, primaryAccount.getId());
    }

    /**
     * Send locate order to a shadow account using copy rule.
     */
    private void sendLocateOrderToShadowAccountWithRule(ExecutionReportContext context, Account primaryAccount,
                                                       Account shadowAccount, CopyRule rule, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = generateShadowClOrdId(context.getClOrdID(), shadowAccountNumber);
        
        // Check if shadow order already exists to avoid duplicates
        if (orderService.orderExists(clOrdId)) {
            log.info("Shadow locate order already exists, skipping duplicate. ShadowClOrdID={}, PrimaryClOrdID={}",
                    clOrdId, context.getClOrdID());
            return;
        }
        
        // Calculate copy quantity based on rule
        BigDecimal primaryQty = context.getOrderQty();
        BigDecimal copyQty = copyRuleService.calculateCopyQuantity(rule, primaryQty);
        
        // Get original route from context
        String originalRoute = context.getExDestination();
        
        // Get locate route from copy rule
        String locateRoute = copyRuleService.getLocateRoute(rule, originalRoute);
        
        // Build order parameters for locate order
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", '1'); // BUY for locate orders
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", copyQty.intValue());
        orderParams.put("account", shadowAccountNumber);
        orderParams.put("exDestination", locateRoute); // Locate route from copy rule
        
        // Set order type from context (default to MARKET if not available)
        char ordType = context.getOrdType() != null ? context.getOrdType() : '1'; // MARKET
        orderParams.put("ordType", ordType);
        
        // Set time in force from context
        Character timeInForce = context.getTimeInForce();
        if (timeInForce == null) {
            timeInForce = '0'; // Default to DAY
        }
        orderParams.put("timeInForce", timeInForce);
        
        // Set price if it's a limit order (from ExecutionReport tag 44)
        if (ordType == '2') { // LIMIT
            if (context.getPrice() != null) {
                orderParams.put("price", context.getPrice().doubleValue());
            } else {
                log.warn("Limit locate order (OrdType={}) has no price in ExecutionReport. ClOrdID={}, LocateClOrdID={}",
                        ordType, context.getClOrdID(), clOrdId);
            }
        }
        
        // Set stop price if it's a stop order (from ExecutionReport tag 99)
        if ((ordType == '3' || ordType == '4') && context.getStopPx() != null) {
            orderParams.put("stopPx", context.getStopPx().doubleValue());
        }

        log.info("Sending locate-only order with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "PrimaryQty={}, CopyQty={}, OriginalRoute={}, LocateRoute={}, RuleId={}",
                clOrdId, primaryAccount.getId(), shadowAccountNumber, shadowAccount.getId(), 
                primaryQty, copyQty, originalRoute, locateRoute, rule.getId());
        
        // Send order first
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        // Create shadow order with "Staged" event asynchronously (non-blocking)
        orderService.createShadowOrderWithStagedEventAsync(
                context.getClOrdID(), // Primary order ClOrdID
                shadowAccount,
                clOrdId, // Shadow order ClOrdID
                context.getSymbol(),
                '1', // BUY for locate orders
                ordType,
                copyQty,
                context.getPrice(),
                context.getStopPx(),
                timeInForce,
                locateRoute,
                initiatorSessionID
        );
        
        log.info("Locate order (3.14) sent with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "CopyQty={}, LocateRoute={}",
                clOrdId, primaryAccount.getId(), shadowAccountNumber, shadowAccount.getId(), copyQty, locateRoute);
    }

    /**
     * Handle ExecutionReport for primary/main account orders.
     * Runs copy trades to shadow accounts when orders are filled.
     * Uses ExecutionReportContext primarily for copying.
     */
    private void handlePrimaryOrderExecutionReport(ExecutionReportContext context) {
        // CRITICAL SAFEGUARD: Double-check this is NOT a copy order before replicating
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.warn("BLOCKED: Attempted to replicate copy order! ClOrdID={}, Account={}. This should never happen.",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        // Get primary account from context
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot replicate order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> primaryAccountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (primaryAccountOpt.isEmpty() || primaryAccountOpt.get().getAccountType() != AccountType.PRIMARY) {
            log.warn("Primary account not found or not PRIMARY type. ClOrdID={}, Account={}", 
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        Account primaryAccount = primaryAccountOpt.get();
        
        // Skip stop limit orders - StopPx is not available in ExecutionReport messages
        if (context.getOrdType() != null && context.getOrdType() == '4') {
            log.info("Skipping stop limit order replication - StopPx not available in ExecutionReport. ClOrdID={}, Account: {}, Symbol={}",
                    context.getClOrdID(), context.getAccount(), context.getSymbol());
            return;
        }
        
        // Skip stop market orders - they are handled in NewOrderHandler when confirmed
        if (context.getOrdType() != null && context.getOrdType() == '3') {
            log.info("Stop market order detected - skipping replication in FillOrderHandler. " +
                    "ClOrdID={}, Account: {}, Symbol={}, OrdType=STOP(3). " +
                    "Stop market orders are copied when confirmed (NewOrderHandler), not when filled.",
                    context.getClOrdID(), context.getAccount(), context.getSymbol());
            return;
        }
        
        // Check if this is a locate order (Side=BUY with ExDestination set to locate route)
        // Regular market/limit and short sell orders are now copied in NewOrderHandler when confirmed
        if (isLocateOrder(context)) {
            handleLocateOrderReplication(context, primaryAccount);
        } else {
            // Market/limit and short sell orders are already copied in NewOrderHandler
            // Only locate orders need to be handled here when filled
            log.debug("Skipping order replication - already handled in NewOrderHandler. ClOrdID={}, Account: {}, Symbol={}, OrdType={}",
                    context.getClOrdID(), context.getAccount(), context.getSymbol(), context.getOrdType());
        }
    }
    

    /**
     * Generate ClOrdID for shadow order.
     */
    private String generateShadowClOrdId(String primaryClOrdId, String shadowAccountNumber) {
        // Simple format: prefix + shadow account + original ClOrdID
        return "COPY-" + shadowAccountNumber + "-" + primaryClOrdId;
    }
}