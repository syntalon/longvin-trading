package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.entities.orders.OrderEvent;
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
 * Handler for replaced order ExecutionReports (ExecType=5, OrdStatus=5).
 * 
 * A replaced order occurs when an order is modified (e.g., price or quantity changed).
 * When a primary account order is replaced, this handler copies the replace order to shadow accounts.
 */
@Component
public class ReplacedOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(ReplacedOrderHandler.class);
    
    private final AccountCacheService accountCacheService;
    private final FixSessionRegistry fixSessionRegistry;
    private final FixMessageSender fixMessageSender;
    private final CopyRuleService copyRuleService;
    private final OrderService orderService;
    
    public ReplacedOrderHandler(AccountCacheService accountCacheService,
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
        return context.getExecType() == '5' && context.getOrdStatus() == '5';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // Log replaced order details
        // OrigClOrdID contains the original ClOrdID before replacement
        String origClOrdID = context.getOrigClOrdID() != null ? context.getOrigClOrdID() : "N/A";
        log.info("Order replaced. ClOrdID={}, OrigClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}, Account={}",
                context.getClOrdID(), origClOrdID, context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty(), context.getAccount());
        
        // Handle copy orders (shadow account orders) - just create event, don't replicate
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.info("Copy order replaced - creating event. ClOrdID={}, OrigClOrdID={}, Account={}, ExecType={}, OrdStatus={}",
                    context.getClOrdID(), origClOrdID, context.getAccount(), context.getExecType(), context.getOrdStatus());
            try {
                // Create event for the shadow order replace
                OrderEvent event = orderService.createEventForShadowOrder(context, sessionID);
                log.info("Successfully created replace event for shadow order: ClOrdID={}, EventId={}, ExecType={}, OrdStatus={}",
                        context.getClOrdID(), event != null ? event.getId() : "null", context.getExecType(), context.getOrdStatus());
            } catch (Exception e) {
                log.error("Error creating event for shadow order replace: ClOrdID={}, OrigClOrdID={}, Account={}, Error={}", 
                        context.getClOrdID(), origClOrdID, context.getAccount(), e.getMessage(), e);
            }
            return;
        }
        
        // Check if this is a primary account order
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot copy replace order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty() || accountOpt.get().getAccountType() != AccountType.PRIMARY) {
            log.debug("Skipping replace order copy - not a primary account. ClOrdID={}, Account={}",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        Account primaryAccount = accountOpt.get();
        
        // Create event for primary account order replace
        try {
            orderService.createEventForOrder(context, sessionID);
            log.debug("Created event for primary order replace: ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
        } catch (Exception e) {
            log.error("Error creating event for primary order replace: ClOrdID={}, Error={}",
                    context.getClOrdID(), e.getMessage(), e);
        }
        
        // Get shadow accounts that match copy rules (same as NewOrderHandler)
        Character ordType = context.getOrdType() != null ? context.getOrdType() : '1'; // Default to MARKET
        String symbol = context.getSymbol();
        BigDecimal quantity = context.getOrderQty();
        
        List<Account> shadowAccounts = copyRuleService.getShadowAccountsForCopy(
                primaryAccount, ordType, symbol, quantity);
        
        log.info("Found {} shadow account(s) for replace order via copy rules: PrimaryAccount={}, PrimaryAccountId={}, OrdType={}, Symbol={}, Qty={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getAccountNumber(), primaryAccount.getId(), ordType, symbol, quantity,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found matching copy rules, cannot copy replace order. ClOrdID={}, AccountId: {}, OrdType={}, Symbol={}, Qty={}",
                    context.getClOrdID(), primaryAccount.getId(), ordType, symbol, quantity);
            return;
        }
        
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy replace order to shadow accounts. ClOrdID={}",
                    context.getClOrdID());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Send replace order to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                // Get copy rule for this account pair
                Optional<CopyRule> ruleOpt = copyRuleService.getRuleForAccountPair(
                        primaryAccount, shadowAccount, ordType, symbol, quantity);
                
                if (ruleOpt.isPresent()) {
                    CopyRule rule = ruleOpt.get();
                    sendReplaceOrderToShadowAccount(context, primaryAccount, shadowAccount, rule, initiatorSessionID);
                } else {
                    // No copy rule found - skip replication
                    log.error("No copy rule found for account pair, cannot copy replace order. PrimaryAccount={}, ShadowAccount={}",
                            primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber());
                }
            } catch (Exception e) {
                log.error("Error sending replace order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Send replace order to a shadow account.
     */
    private void sendReplaceOrderToShadowAccount(ExecutionReportContext context, Account primaryAccount,
                                                 Account shadowAccount, CopyRule rule, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        
        // Determine original and new shadow ClOrdIDs
        // Shadow order ClOrdID pattern: COPY-{shadowAccount}-{primaryClOrdID}
        // FIX protocol requires ClOrdID != OrigClOrdID in replace request
        
        // Original shadow ClOrdID: Use origClOrdID if available, otherwise use current ClOrdID
        String originalPrimaryClOrdId = (context.getOrigClOrdID() != null && !context.getOrigClOrdID().equals("N/A")) 
                ? context.getOrigClOrdID() 
                : context.getClOrdID();
        String originalShadowClOrdId = "COPY-" + shadowAccountNumber + "-" + originalPrimaryClOrdId;
        
        // New shadow ClOrdID: Use primary's new ClOrdID if it changed, otherwise generate unique one
        String newPrimaryClOrdId = context.getClOrdID();
        String newShadowClOrdId = "COPY-" + shadowAccountNumber + "-" + newPrimaryClOrdId;
        
        // FIX protocol requires ClOrdID != OrigClOrdID in replace request
        // If primary ClOrdID didn't change (only price/qty changed), we need a different ClOrdID
        if (originalShadowClOrdId.equals(newShadowClOrdId)) {
            // Primary ClOrdID didn't change, but FIX requires different ClOrdIDs for replace
            // Use a simple incrementing suffix to make it unique
            // Note: We don't update the shadow order entity's ClOrdID - it stays as the base pattern
            newShadowClOrdId = originalShadowClOrdId + "-R" + System.currentTimeMillis();
            log.info("Primary ClOrdID unchanged, using unique ClOrdID for replace request: {} -> {}",
                    originalShadowClOrdId, newShadowClOrdId);
        }
        
        // Calculate new quantity based on copy rule
        BigDecimal primaryQty = context.getOrderQty();
        BigDecimal copyQty = copyRuleService.calculateCopyQuantity(rule, primaryQty);
        
        // Get original route from context
        String originalRoute = context.getExDestination();
        
        // Get target route from copy rule
        String targetRoute = copyRuleService.getTargetRoute(rule, originalRoute, false);
        
        // Build order parameters from ExecutionReport context
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("origClOrdID", originalShadowClOrdId); // Original shadow ClOrdID
        orderParams.put("clOrdID", newShadowClOrdId); // New shadow ClOrdID (different from OrigClOrdID)
        orderParams.put("side", context.getSide());
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", copyQty.intValue());
        orderParams.put("account", shadowAccountNumber);
        
        // Set order type from context
        char ordType = context.getOrdType() != null ? context.getOrdType() : '1'; // Default to MARKET
        orderParams.put("ordType", ordType);
        
        // Set time in force from context
        Character timeInForce = context.getTimeInForce();
        if (timeInForce == null) {
            timeInForce = '0'; // Default to DAY
        }
        orderParams.put("timeInForce", timeInForce);
        
        // Set price if it's a limit order
        if (ordType == '2') { // LIMIT
            if (context.getPrice() != null) {
                orderParams.put("price", context.getPrice().doubleValue());
            } else {
                log.warn("Limit order (OrdType={}) has no price in ExecutionReport. ClOrdID={}, ShadowClOrdID={}",
                        ordType, context.getClOrdID(), newShadowClOrdId);
            }
        }
        
        // Set stop price if it's a stop order
        if ((ordType == '3' || ordType == '4') && context.getStopPx() != null) {
            orderParams.put("stopPx", context.getStopPx().doubleValue());
        }
        
        // Set route from copy rule
        if (targetRoute != null && !targetRoute.isBlank()) {
            orderParams.put("exDestination", targetRoute);
        }
        
        log.info("Sending replace order to shadow account: OrigShadowClOrdID={}, NewShadowClOrdID={}, " +
                "PrimaryClOrdID={}, ShadowAccount={}, ShadowAccountId={}, " +
                "PrimaryQty={}, CopyQty={}, OriginalRoute={}, TargetRoute={}, RuleId={}",
                originalShadowClOrdId, newShadowClOrdId, context.getClOrdID(),
                shadowAccountNumber, shadowAccount.getId(), primaryQty, copyQty, originalRoute, targetRoute, rule.getId());
        
        // Send replace order
        fixMessageSender.sendOrderCancelReplaceRequest(initiatorSessionID, orderParams);
        
        log.info("Replace order sent to shadow account: OrigShadowClOrdID={}, NewShadowClOrdID={}, ShadowAccount={}, ShadowAccountId={}",
                originalShadowClOrdId, newShadowClOrdId, shadowAccountNumber, shadowAccount.getId());
    }
}

