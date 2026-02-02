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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final OrderRepository orderRepository;
    
    public ReplacedOrderHandler(AccountCacheService accountCacheService,
                                FixSessionRegistry fixSessionRegistry,
                                FixMessageSender fixMessageSender,
                                OrderRepository orderRepository) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.fixMessageSender = fixMessageSender;
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
        
        // Find all shadow orders linked to the original primary order
        if (origClOrdID == null || origClOrdID.equals("N/A")) {
            log.warn("OrigClOrdID is missing, cannot find shadow orders to replace. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        List<Order> shadowOrders = orderRepository.findByPrimaryOrderClOrdId(origClOrdID);
        
        // Filter to only shadow orders (those with primaryOrderClOrdId set)
        List<Order> shadowOrdersOnly = shadowOrders.stream()
                .filter(order -> order.getPrimaryOrderClOrdId() != null && 
                        order.getPrimaryOrderClOrdId().equals(origClOrdID))
                .toList();
        
        if (shadowOrdersOnly.isEmpty()) {
            log.debug("No shadow orders found for primary order replacement. OrigClOrdID={}, NewClOrdID={}",
                    origClOrdID, context.getClOrdID());
            return;
        }
        
        log.info("Found {} shadow order(s) to replace for primary order. OrigClOrdID={}, NewClOrdID={}",
                shadowOrdersOnly.size(), origClOrdID, context.getClOrdID());
        
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy replace order to shadow accounts. ClOrdID={}",
                    context.getClOrdID());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Send replace order to each shadow account
        for (Order shadowOrder : shadowOrdersOnly) {
            try {
                sendReplaceOrderToShadowAccount(context, shadowOrder, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error sending replace order to shadow account {} (AccountId: {}): {}", 
                        shadowOrder.getAccount().getAccountNumber(), shadowOrder.getAccount().getId(), 
                        e.getMessage(), e);
            }
        }
    }
    
    /**
     * Send replace order to a shadow account.
     */
    private void sendReplaceOrderToShadowAccount(ExecutionReportContext context, Order shadowOrder, 
                                                 SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowOrder.getAccount().getAccountNumber();
        String originalShadowClOrdId = shadowOrder.getFixClOrdId();
        
        // Generate new ClOrdID for shadow order: COPY-{shadowAccount}-{newPrimaryClOrdID}
        String newShadowClOrdId = "COPY-" + shadowAccountNumber + "-" + context.getClOrdID();
        
        // Calculate new quantity (use the same ratio if needed, or just use the new quantity from context)
        // For now, use the new quantity from context directly
        BigDecimal newQty = context.getOrderQty();
        if (newQty == null) {
            log.warn("OrderQty is missing in ExecutionReport, using original quantity. ClOrdID={}", 
                    context.getClOrdID());
            newQty = shadowOrder.getOrderQty();
        }
        
        // Build order parameters from ExecutionReport context
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("origClOrdID", originalShadowClOrdId); // Original shadow ClOrdID
        orderParams.put("clOrdID", newShadowClOrdId); // New shadow ClOrdID
        orderParams.put("side", context.getSide());
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", newQty.intValue());
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
        
        // Set route if available
        if (shadowOrder.getExDestination() != null && !shadowOrder.getExDestination().isBlank()) {
            orderParams.put("exDestination", shadowOrder.getExDestination());
        }
        
        log.info("Sending replace order to shadow account: OrigShadowClOrdID={}, NewShadowClOrdID={}, " +
                "PrimaryOrigClOrdID={}, PrimaryNewClOrdID={}, ShadowAccount={}, ShadowAccountId={}, Qty={}",
                originalShadowClOrdId, newShadowClOrdId, context.getOrigClOrdID(), context.getClOrdID(),
                shadowAccountNumber, shadowOrder.getAccount().getId(), newQty);
        
        // Send replace order
        fixMessageSender.sendOrderCancelReplaceRequest(initiatorSessionID, orderParams);
        
        log.info("Replace order sent to shadow account: NewShadowClOrdID={}, ShadowAccount={}, ShadowAccountId={}",
                newShadowClOrdId, shadowAccountNumber, shadowOrder.getAccount().getId());
    }
}

