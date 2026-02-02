package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
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
 * Handler for new order ExecutionReports (ExecType=0, OrdStatus=0).
 * 
 * When a new order is confirmed:
 * - For market orders (OrdType='1'): Copies to shadow accounts immediately
 * - For limit orders (OrdType='2'): Copies to shadow accounts immediately
 * - For short sell orders (Side='3' or '4'): Copies to shadow accounts immediately
 * - For stop market orders (OrdType='3'): Copies to shadow accounts (StopPx not required)
 * - For stop limit orders (OrdType='4'): Not copied (StopPx not available in ExecutionReport)
 * - For locate orders: Not copied here (handled separately in FillOrderHandler/LocateResponseHandler)
 */
@Component
public class NewOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);
    
    private final AccountCacheService accountCacheService;
    private final FixSessionRegistry fixSessionRegistry;
    private final FixMessageSender fixMessageSender;
    private final CopyRuleService copyRuleService;
    private final OrderService orderService;
    private final RouteService routeService;
    
    public NewOrderHandler(AccountCacheService accountCacheService,
                          FixSessionRegistry fixSessionRegistry,
                          FixMessageSender fixMessageSender,
                          CopyRuleService copyRuleService,
                          OrderService orderService,
                          RouteService routeService) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.fixMessageSender = fixMessageSender;
        this.copyRuleService = copyRuleService;
        this.orderService = orderService;
        this.routeService = routeService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '0' && context.getOrdStatus() == '0';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("New order confirmed. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty());
        
        // Handle copy orders (shadow account orders) - create event only, no order creation/update
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            try {
                orderService.createEventForShadowOrder(context, sessionID);
                log.info("New event created for shadow order: ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}",
                        context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus());
            } catch (Exception e) {
                log.error("Error creating event for shadow order: ClOrdID={}, Account={}, Error={}", 
                        context.getClOrdID(), context.getAccount(), e.getMessage(), e);
            }
            return;
        }
        
        // For primary account orders: create order and New event
        try {
            orderService.createOrUpdateOrderForPrimaryAccount(context, sessionID);
            log.info("Order persisted successfully for primary account: ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                    context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                    context.getSide(), context.getOrderQty());
        } catch (Exception e) {
            log.error("Error persisting order: ClOrdID={}, Account={}, Error={}", 
                    context.getClOrdID(), context.getAccount(), e.getMessage(), e);
        }

        // Skip stop limit orders (OrdType='4') - StopPx is not available in ExecutionReport
        if (context.getOrdType() != null && context.getOrdType() == '4') {
            log.info("Stop limit order confirmed - NOT copying to shadow accounts (StopPx not available in ExecutionReport). " +
                            "ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                    context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                    context.getSide(), context.getOrderQty());
            return;
        }
        
        // Get account to check if it's a primary account
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot copy order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty() || accountOpt.get().getAccountType() != AccountType.PRIMARY) {
            log.debug("Skipping order copy - not a primary account. ClOrdID={}, Account={}",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        Account primaryAccount = accountOpt.get();
        
        // Skip locate orders - they are handled separately in FillOrderHandler/LocateResponseHandler
        if (isLocateOrder(context)) {
            log.info("Locate order confirmed - NOT copying here (handled separately). ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                    context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                    context.getSide(), context.getOrderQty());
            return;
        }
        
        // Handle stop market orders (OrdType='3') - copy to shadow accounts
        // Stop market orders don't require StopPx, so we can copy them
        if (context.getOrdType() != null && context.getOrdType() == '3') {
            log.info("Stop market order confirmed - copying to shadow accounts. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                    context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                    context.getSide(), context.getOrderQty());
            copyStopMarketOrderToShadowAccounts(context, primaryAccount);
            return;
        }
        
        // Handle market orders (OrdType='1') and limit orders (OrdType='2') - copy to shadow accounts
        Character ordType = context.getOrdType();
        if (ordType != null && (ordType == '1' || ordType == '2')) {
            String orderTypeLabel = ordType == '1' ? "market" : "limit";
            String orderTypeLabelCap = ordType == '1' ? "Market" : "Limit";
            if (context.isShortOrder()) {
                log.info("Short {} order confirmed - copying to shadow accounts. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                        orderTypeLabel, context.getClOrdID(), context.getOrderID(), 
                        context.getSymbol(), context.getSide(), context.getOrderQty());
            } else {
                log.info("{} order confirmed - copying to shadow accounts. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                        orderTypeLabelCap, context.getClOrdID(), context.getOrderID(), 
                        context.getSymbol(), context.getSide(), context.getOrderQty());
            }
            copyOrderToShadowAccounts(context, primaryAccount);
            return;
        }
        
        // For other order types, log but don't copy
        log.info("Order type {} not handled for immediate copy. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                ordType, context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty());
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
     * Copy order (market/limit, regular or short) to shadow accounts.
     */
    private void copyOrderToShadowAccounts(ExecutionReportContext context, Account primaryAccount) {
        log.info("Processing order copy on new order: ClOrdID={}, Account: {}, AccountId: {}, Symbol={}, Side={}, Qty={}",
                context.getClOrdID(), context.getAccount(), primaryAccount.getId(), context.getSymbol(), 
                context.getSide(), context.getOrderQty());

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy order to shadow accounts. ClOrdID={}, AccountId: {}",
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
        
        log.info("Found {} shadow account(s) for order copy via copy rules: PrimaryAccount={}, PrimaryAccountId={}, OrdType={}, Symbol={}, Qty={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getAccountNumber(), primaryAccount.getId(), ordType, symbol, quantity,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found matching copy rules, cannot copy order. ClOrdID={}, AccountId: {}, OrdType={}, Symbol={}, Qty={}",
                    context.getClOrdID(), primaryAccount.getId(), ordType, symbol, quantity);
            return;
        }

        // Send orders to each shadow account using copy rules
        for (Account shadowAccount : shadowAccounts) {
            try {
                // Get copy rule for this account pair
                Optional<CopyRule> ruleOpt = copyRuleService.getRuleForAccountPair(
                        primaryAccount, shadowAccount, ordType, symbol, quantity);
                
                if (ruleOpt.isPresent()) {
                    CopyRule rule = ruleOpt.get();
                    sendOrderToShadowAccountWithRule(context, primaryAccount, shadowAccount, rule, initiatorSessionID);
                } else {
                    // No copy rule found - skip replication
                    log.error("No copy rule found for account pair, cannot copy order. PrimaryAccount={}, ShadowAccount={}",
                            primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber());
                }
            } catch (Exception e) {
                log.error("Error copying order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Copy stop market order to shadow accounts.
     * Stop market orders don't require StopPx, so we can copy them without it.
     */
    private void copyStopMarketOrderToShadowAccounts(ExecutionReportContext context, Account primaryAccount) {
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy stop market order to shadow accounts. ClOrdID={}, AccountId: {}",
                    context.getClOrdID(), primaryAccount.getId());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Get shadow accounts that match copy rules
        Character ordType = context.getOrdType();
        String symbol = context.getSymbol();
        BigDecimal quantity = context.getOrderQty();
        
        List<Account> shadowAccounts = copyRuleService.getShadowAccountsForCopy(
                primaryAccount, ordType, symbol, quantity);
        
        log.info("Found {} shadow account(s) for stop market order copy via copy rules: PrimaryAccount={}, PrimaryAccountId={}, OrdType={}, Symbol={}, Qty={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getAccountNumber(), primaryAccount.getId(), ordType, symbol, quantity,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found matching copy rules, cannot copy stop market order. ClOrdID={}, AccountId: {}, OrdType={}, Symbol={}, Qty={}",
                    context.getClOrdID(), primaryAccount.getId(), ordType, symbol, quantity);
            return;
        }
        
        // Send stop market order to each shadow account using copy rules
        for (Account shadowAccount : shadowAccounts) {
            try {
                // Get copy rule for this account pair
                Optional<CopyRule> ruleOpt = copyRuleService.getRuleForAccountPair(
                        primaryAccount, shadowAccount, ordType, symbol, quantity);
                
                if (ruleOpt.isPresent()) {
                    CopyRule rule = ruleOpt.get();
                    sendStopMarketOrderToShadowAccountWithRule(context, shadowAccount, primaryAccount, rule, initiatorSessionID);
                } else {
                    // No copy rule found - skip replication
                    log.error("No copy rule found for account pair, cannot copy stop market order. PrimaryAccount={}, ShadowAccount={}",
                            primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber());
                }
            } catch (Exception e) {
                log.error("Error copying stop market order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Send stop market order to a shadow account using copy rule.
     */
    private void sendStopMarketOrderToShadowAccountWithRule(ExecutionReportContext context, Account shadowAccount, 
                                                           Account primaryAccount, CopyRule rule, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = "COPY-" + shadowAccountNumber + "-" + context.getClOrdID();
        
        // Calculate copy quantity based on rule
        BigDecimal primaryQty = context.getOrderQty();
        BigDecimal copyQty = copyRuleService.calculateCopyQuantity(rule, primaryQty);
        
        // Get original route from context
        String originalRoute = context.getExDestination();
        
        // Get copy route from copy rule (not locate route for stop orders)
        String targetRoute = copyRuleService.getCopyRoute(rule, originalRoute);
        
        // Build order parameters
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", context.getSide());
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", copyQty.intValue());
        orderParams.put("account", shadowAccountNumber);
        orderParams.put("ordType", '3'); // STOP market order
        
        // TimeInForce - use from context if available, otherwise default to DAY
        Character timeInForce = context.getTimeInForce();
        if (timeInForce == null) {
            timeInForce = '0'; // Default to DAY if not available
        }
        orderParams.put("timeInForce", timeInForce);
        
        // Stop market orders don't require StopPx - they trigger at market price when stop condition is met
        if (context.getStopPx() != null) {
            orderParams.put("stopPx", context.getStopPx().doubleValue());
        }
        
        // Set route from copy rule
        if (targetRoute != null && !targetRoute.isBlank()) {
            orderParams.put("exDestination", targetRoute);
        }
        
        Long primaryAccountId = primaryAccount.getId();
        log.info("Sending stop market order copy with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "PrimaryQty={}, CopyQty={}, OriginalRoute={}, TargetRoute={}, RuleId={}",
                clOrdId, primaryAccountId, shadowAccountNumber, shadowAccount.getId(), 
                primaryQty, copyQty, originalRoute, targetRoute, rule.getId());
        
        // Send order first
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        // Create shadow order with "Staged" event asynchronously (non-blocking)
        orderService.createShadowOrderWithStagedEventAsync(
                context.getClOrdID(), // Primary order ClOrdID
                shadowAccount,
                clOrdId, // Shadow order ClOrdID
                context.getSymbol(),
                context.getSide(),
                '3', // STOP market order
                copyQty,
                context.getPrice(),
                context.getStopPx(),
                timeInForce,
                targetRoute,
                initiatorSessionID
        );
        
        log.info("Stop market order copy sent with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "CopyQty={}, TargetRoute={}",
                clOrdId, primaryAccountId, shadowAccountNumber, shadowAccount.getId(), copyQty, targetRoute);
    }
    
    /**
     * Send order to a shadow account using copy rule.
     * Used for market, limit, and short orders.
     */
    private void sendOrderToShadowAccountWithRule(ExecutionReportContext context, Account primaryAccount,
                                                 Account shadowAccount, CopyRule rule, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = generateShadowClOrdId(context.getClOrdID(), shadowAccountNumber);
        
        // Check if shadow order already exists to avoid duplicates
        if (orderService.orderExists(clOrdId)) {
            log.info("Shadow order already exists, skipping duplicate. ShadowClOrdID={}, PrimaryClOrdID={}",
                    clOrdId, context.getClOrdID());
            return;
        }
        
        // Calculate copy quantity based on rule
        BigDecimal primaryQty = context.getOrderQty();
        BigDecimal copyQty = copyRuleService.calculateCopyQuantity(rule, primaryQty);
        
        // Get original route from context
        String originalRoute = context.getExDestination();
        
        // Determine if this is a locate order (shouldn't happen here, but check anyway)
        boolean isLocateOrder = originalRoute != null && routeService.isLocateRoute(originalRoute);
        
        // Get target route from copy rule
        String targetRoute = copyRuleService.getTargetRoute(rule, originalRoute, isLocateOrder);
        
        // Build order parameters from ExecutionReport context
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", context.getSide());
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", copyQty.intValue());
        orderParams.put("account", shadowAccountNumber);
        
        // Set order type from context (from ExecutionReport tag 40)
        char ordType = context.getOrdType() != null ? context.getOrdType() : '1'; // Default to MARKET
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
                log.warn("Limit order (OrdType={}) has no price in ExecutionReport. ClOrdID={}, CopyClOrdID={}",
                        ordType, context.getClOrdID(), clOrdId);
            }
        }
        
        // Set stop price if it's a stop order (from ExecutionReport tag 99)
        if ((ordType == '3' || ordType == '4') && context.getStopPx() != null) {
            orderParams.put("stopPx", context.getStopPx().doubleValue());
        }
        
        // Set route from copy rule
        if (targetRoute != null && !targetRoute.isBlank()) {
            orderParams.put("exDestination", targetRoute);
        }
        
        log.info("Sending copy order with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "PrimaryQty={}, CopyQty={}, OriginalRoute={}, TargetRoute={}, IsLocate={}, RuleId={}",
                clOrdId, primaryAccount.getId(), shadowAccountNumber, shadowAccount.getId(), 
                primaryQty, copyQty, originalRoute, targetRoute, isLocateOrder, rule.getId());
        
        // Send order first
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        // Create shadow order asynchronously (non-blocking)
        orderService.createShadowOrderWithStagedEventAsync(
                context.getClOrdID(), // Primary order ClOrdID
                shadowAccount,
                clOrdId, // Shadow order ClOrdID
                context.getSymbol(),
                context.getSide(),
                ordType,
                copyQty,
                context.getPrice(),
                context.getStopPx(),
                timeInForce,
                targetRoute,
                initiatorSessionID
        );
        
        log.info("Copy order sent with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "CopyQty={}, TargetRoute={}",
                clOrdId, primaryAccount.getId(), shadowAccountNumber, shadowAccount.getId(), copyQty, targetRoute);
    }
    
    /**
     * Generate ClOrdID for shadow order.
     */
    private String generateShadowClOrdId(String primaryClOrdId, String shadowAccountNumber) {
        // Simple format: prefix + shadow account + original ClOrdID
        return "COPY-" + shadowAccountNumber + "-" + primaryClOrdId;
    }

}