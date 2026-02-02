package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.repository.OrderRepository;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.CopyRuleService;
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
    private final OrderRepository orderRepository;
    
    public ReplacedOrderHandler(AccountCacheService accountCacheService,
                                FixSessionRegistry fixSessionRegistry,
                                FixMessageSender fixMessageSender,
                                CopyRuleService copyRuleService,
                                OrderRepository orderRepository) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.fixMessageSender = fixMessageSender;
        this.copyRuleService = copyRuleService;
        this.orderRepository = orderRepository;
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
        
        // Handle copy orders (shadow account orders) - just log, don't replicate
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.info("Copy order replaced - no further action needed. ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
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
        
        // Find existing shadow order to get its current ClOrdID
        // This is needed because OrigClOrdID might not be in the ExecutionReport
        List<Order> shadowOrders = orderRepository.findByPrimaryOrderClOrdId(context.getClOrdID());
        Optional<Order> existingShadowOrder = shadowOrders.stream()
                .filter(order -> order.getAccount() != null && 
                        order.getAccount().getId().equals(shadowAccount.getId()) &&
                        order.getPrimaryOrderClOrdId() != null &&
                        order.getPrimaryOrderClOrdId().equals(context.getClOrdID()))
                .findFirst();
        
        String originalShadowClOrdId;
        String newShadowClOrdId;
        
        if (existingShadowOrder.isPresent()) {
            // Found existing shadow order - use its actual ClOrdID as the original
            originalShadowClOrdId = existingShadowOrder.get().getFixClOrdId();
            log.debug("Found existing shadow order: ClOrdID={}, PrimaryClOrdID={}",
                    originalShadowClOrdId, context.getClOrdID());
        } else {
            // No existing shadow order found - construct it from primary ClOrdID
            // Use origClOrdID if available, otherwise use current ClOrdID
            String originalPrimaryClOrdId = (context.getOrigClOrdID() != null && !context.getOrigClOrdID().equals("N/A")) 
                    ? context.getOrigClOrdID() 
                    : context.getClOrdID();
            originalShadowClOrdId = "COPY-" + shadowAccountNumber + "-" + originalPrimaryClOrdId;
            log.warn("No existing shadow order found. Constructed original ClOrdID: {}, PrimaryClOrdID={}, OrigClOrdID={}",
                    originalShadowClOrdId, context.getClOrdID(), context.getOrigClOrdID());
        }
        
        // New shadow ClOrdID: COPY-{shadowAccount}-{newPrimaryClOrdID}
        newShadowClOrdId = "COPY-" + shadowAccountNumber + "-" + context.getClOrdID();
        
        // If both ClOrdIDs are the same, we still need to send the replace if other fields changed
        // But FIX protocol requires different ClOrdIDs for replace - generate a new one if needed
        if (originalShadowClOrdId.equals(newShadowClOrdId)) {
            // Primary ClOrdID didn't change, but we still need to replace (price/qty change)
            // Generate a new ClOrdID by appending a suffix
            newShadowClOrdId = originalShadowClOrdId + "-REPLACE";
            log.info("Primary ClOrdID unchanged, generating new shadow ClOrdID for replace: {} -> {}",
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
        orderParams.put("clOrdID", newShadowClOrdId); // New shadow ClOrdID
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
        
        log.info("Replace order sent to shadow account: NewShadowClOrdID={}, ShadowAccount={}, ShadowAccountId={}",
                newShadowClOrdId, shadowAccountNumber, shadowAccount.getId());
    }
}

