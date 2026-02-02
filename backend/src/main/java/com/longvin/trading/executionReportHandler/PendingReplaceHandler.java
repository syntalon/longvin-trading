package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.Optional;

/**
 * Handler for pending replace ExecutionReports (ExecType=E, OrdStatus=E).
 * 
 * This status occurs when a replace order has been sent but not yet confirmed.
 * The broker sends this intermediate status before the final "Replaced" status (ExecType=5, OrdStatus=5).
 * 
 * We need to create events for both primary and shadow accounts to track the order lifecycle.
 */
@Component
public class PendingReplaceHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(PendingReplaceHandler.class);
    
    private final OrderService orderService;
    private final AccountCacheService accountCacheService;
    
    public PendingReplaceHandler(OrderService orderService,
                                 AccountCacheService accountCacheService) {
        this.orderService = orderService;
        this.accountCacheService = accountCacheService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == 'E' && context.getOrdStatus() == 'E';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // Log pending replace order details
        String origClOrdID = context.getOrigClOrdID() != null ? context.getOrigClOrdID() : "N/A";
        log.info("Order pending replace. ClOrdID={}, OrigClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}, Account={}",
                context.getClOrdID(), origClOrdID, context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty(), context.getAccount());
        
        // Handle copy orders (shadow account orders) - create event
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.info("Copy order pending replace - creating event. ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
            try {
                orderService.createEventForShadowOrder(context, sessionID);
            } catch (Exception e) {
                log.error("Error creating event for shadow order pending replace: ClOrdID={}, Error={}",
                        context.getClOrdID(), e.getMessage(), e);
            }
            return;
        }
        
        // Check if this is a primary account order
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot process pending replace. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty()) {
            log.warn("Account not found for pending replace: ClOrdID={}, Account={}", 
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        Account account = accountOpt.get();
        
        // Create event for primary account order pending replace
        if (account.getAccountType() == AccountType.PRIMARY) {
            try {
                orderService.createEventForOrder(context, sessionID);
                log.debug("Created event for primary order pending replace: ClOrdID={}, OrigClOrdID={}",
                        context.getClOrdID(), origClOrdID);
            } catch (Exception e) {
                log.error("Error creating event for primary order pending replace: ClOrdID={}, Error={}",
                        context.getClOrdID(), e.getMessage(), e);
            }
        } else {
            // Shadow account order (not COPY- prefix, but account type is SHADOW)
            try {
                orderService.createEventForShadowOrder(context, sessionID);
                log.debug("Created event for shadow order pending replace: ClOrdID={}, OrigClOrdID={}",
                        context.getClOrdID(), origClOrdID);
            } catch (Exception e) {
                log.error("Error creating event for shadow order pending replace: ClOrdID={}, Error={}",
                        context.getClOrdID(), e.getMessage(), e);
            }
        }
    }
}

