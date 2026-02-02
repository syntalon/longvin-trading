package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.repository.OrderRepository;
import com.longvin.trading.service.AccountCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;

import java.util.List;
import java.util.Optional;

/**
 * Handler for canceled order ExecutionReports (ExecType=4, OrdStatus=4).
 * 
 * When a primary account order is canceled, this handler copies the cancel to shadow accounts.
 */
@Component
public class CanceledOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(CanceledOrderHandler.class);
    
    private final AccountCacheService accountCacheService;
    private final FixSessionRegistry fixSessionRegistry;
    private final OrderRepository orderRepository;
    
    public CanceledOrderHandler(AccountCacheService accountCacheService,
                               FixSessionRegistry fixSessionRegistry,
                               OrderRepository orderRepository) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.orderRepository = orderRepository;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '4' && context.getOrdStatus() == '4';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // Log canceled order details
        String origClOrdID = context.getOrigClOrdID() != null ? context.getOrigClOrdID() : context.getClOrdID();
        log.info("Order canceled. ClOrdID={}, OrigClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}, Account={}",
                context.getClOrdID(), origClOrdID, context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty(), context.getAccount());
        
        // Handle copy orders (shadow account orders) - just log, don't replicate
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.info("Copy order canceled - no further action needed. ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
            return;
        }
        
        // Check if this is a primary account order
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot copy cancel order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty() || accountOpt.get().getAccountType() != AccountType.PRIMARY) {
            log.debug("Skipping cancel order copy - not a primary account. ClOrdID={}, Account={}",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        // Find shadow orders linked to the primary order
        // Use origClOrdID if available, otherwise use current ClOrdID
        String primaryClOrdId = origClOrdID;
        
        List<Order> shadowOrders = orderRepository.findByPrimaryOrderClOrdId(primaryClOrdId);
        
        // Filter to only shadow orders (those with primaryOrderClOrdId set)
        List<Order> shadowOrdersOnly = shadowOrders.stream()
                .filter(order -> order.getAccount() != null && 
                        order.getAccount().getAccountType() == AccountType.SHADOW &&
                        order.getPrimaryOrderClOrdId() != null && 
                        order.getPrimaryOrderClOrdId().equals(primaryClOrdId))
                .toList();
        
        if (shadowOrdersOnly.isEmpty()) {
            log.debug("No shadow orders found for primary order cancel. ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
            return;
        }
        
        log.info("Found {} shadow order(s) to cancel for primary order. ClOrdID={}, OrigClOrdID={}",
                shadowOrdersOnly.size(), context.getClOrdID(), origClOrdID);
        
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy cancel order to shadow accounts. ClOrdID={}",
                    context.getClOrdID());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Send cancel to each shadow account
        for (Order shadowOrder : shadowOrdersOnly) {
            try {
                String shadowClOrdId = shadowOrder.getFixClOrdId();
                String shadowAccountNumber = shadowOrder.getAccount().getAccountNumber();
                sendCancelOrderToShadowAccount(shadowClOrdId, shadowAccountNumber, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error sending cancel order to shadow account {} (AccountId: {}): {}", 
                        shadowOrder.getAccount().getAccountNumber(), shadowOrder.getAccount().getId(), 
                        e.getMessage(), e);
            }
        }
    }
    
    /**
     * Send cancel order request to a shadow account.
     */
    private void sendCancelOrderToShadowAccount(String shadowClOrdId, String shadowAccountNumber, 
                                               SessionID initiatorSessionID) {
        Message cancelRequest = new Message();
        quickfix.Message.Header header = cancelRequest.getHeader();

        header.setString(MsgType.FIELD, MsgType.ORDER_CANCEL_REQUEST);
        header.setString(SenderCompID.FIELD, initiatorSessionID.getSenderCompID());
        header.setString(TargetCompID.FIELD, initiatorSessionID.getTargetCompID());

        cancelRequest.setString(OrigClOrdID.FIELD, shadowClOrdId);
        cancelRequest.setString(ClOrdID.FIELD, shadowClOrdId); // Use same ClOrdID for cancel

        try {
            Session.sendToTarget(cancelRequest, initiatorSessionID);
            log.info("Order Cancel Request sent to shadow account: ClOrdID={}, ShadowAccount={}",
                    shadowClOrdId, shadowAccountNumber);
        } catch (SessionNotFound e) {
            log.error("Failed to send Order Cancel Request to shadow account: ClOrdID={}, ShadowAccount={}",
                    shadowClOrdId, shadowAccountNumber, e);
        }
    }
}

