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
 * - For stop market orders (OrdType='3'): Copies to shadow accounts (StopPx not required)
 * - For stop limit orders (OrdType='4'): Not copied (StopPx not available in ExecutionReport)
 * - For other orders: Logs the confirmation. Order replication is handled by FillOrderHandler when orders are filled.
 */
@Component
public class NewOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);
    
    private final AccountCacheService accountCacheService;
    private final FixSessionRegistry fixSessionRegistry;
    private final FixMessageSender fixMessageSender;
    private final CopyRuleService copyRuleService;
    private final OrderService orderService;
    
    public NewOrderHandler(AccountCacheService accountCacheService,
                          FixSessionRegistry fixSessionRegistry,
                          FixMessageSender fixMessageSender,
                          CopyRuleService copyRuleService,
                          OrderService orderService) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.fixMessageSender = fixMessageSender;
        this.copyRuleService = copyRuleService;
        this.orderService = orderService;
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
        
        // Handle copy orders - update order and create event
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            try {
                orderService.updateCopyOrderAndCreateEvent(context, sessionID);
                log.info("Copy order updated and event created: ClOrdID={}, OrderID={}, ExecType={}, OrdStatus={}",
                        context.getClOrdID(), context.getOrderID(), context.getExecType(), context.getOrdStatus());
            } catch (Exception e) {
                log.error("Error updating copy order: ClOrdID={}, Account={}, Error={}", 
                        context.getClOrdID(), context.getAccount(), e.getMessage(), e);
            }
            return;
        }
        
        // Persist order (service handles validation for primary accounts)
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
        
        // Handle stop market orders (OrdType='3') - copy to shadow accounts
        // Stop market orders don't require StopPx, so we can copy them
        if (context.getOrdType() != null && context.getOrdType() == '3') {
            // Get account to check if it's a primary account
            if (context.getAccount() != null) {
                Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
                if (accountOpt.isPresent() && accountOpt.get().getAccountType() == AccountType.PRIMARY) {
                    log.info("Stop market order confirmed - copying to shadow accounts. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                            context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                            context.getSide(), context.getOrderQty());
                    copyStopMarketOrderToShadowAccounts(context);
                } else {
                    log.debug("Skipping stop market order copy - not a primary account. ClOrdID={}, Account={}",
                            context.getClOrdID(), context.getAccount());
                }
            } else {
                log.warn("Cannot copy stop market order - Account field missing in ExecutionReport. ClOrdID={}",
                        context.getClOrdID());
            }
            return;
        }

    }
    
    /**
     * Copy stop market order to shadow accounts.
     * Stop market orders don't require StopPx, so we can copy them without it.
     */
    private void copyStopMarketOrderToShadowAccounts(ExecutionReportContext context) {
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy stop market order to shadow accounts. ClOrdID={}, Account: {}",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Get primary account to filter shadow accounts by strategy_key
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot copy stop market order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> primaryAccountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (primaryAccountOpt.isEmpty()) {
            log.warn("Primary account not found for account number: {}, cannot copy stop market order. ClOrdID={}", 
                    context.getAccount(), context.getClOrdID());
            return;
        }
        
        Account primaryAccount = primaryAccountOpt.get();
        
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
        
        // Send order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        log.info("Stop market order copy sent with rule: ClOrdID={}, PrimaryAccountId={}, ShadowAccount={}, ShadowAccountId={}, " +
                "CopyQty={}, TargetRoute={}",
                clOrdId, primaryAccountId, shadowAccountNumber, shadowAccount.getId(), copyQty, targetRoute);
        
        // Persist shadow account order
        try {
            orderService.createShadowAccountOrder(
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
                    targetRoute
            );
        } catch (Exception e) {
            log.error("Error persisting shadow account order: ShadowClOrdID={}, Error={}", 
                    clOrdId, e.getMessage(), e);
        }
    }

}