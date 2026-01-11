package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.ShortOrderDraftService;
import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.List;
import java.util.Optional;

/**
 * Handler for new order ExecutionReports (ExecType=0, OrdStatus=0).
 * 
 * When a new order is confirmed:
 * 1. For regular orders: Log and update status
 * 2. For short orders (Side=5): Initiate locate request workflow
 */
@Component
public class NewOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);
    
    private final ShortOrderProcessingService shortOrderProcessingService;
    private final ShortOrderDraftService shortOrderDraftService;
    private final AccountCacheService accountCacheService;

    public NewOrderHandler(ShortOrderProcessingService shortOrderProcessingService,
                          ShortOrderDraftService shortOrderDraftService,
                          AccountCacheService accountCacheService) {
        this.shortOrderProcessingService = shortOrderProcessingService;
        this.shortOrderDraftService = shortOrderDraftService;
        this.accountCacheService = accountCacheService;
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

        // For short sell orders, initiate the locate process
        if (context.isShortOrder()) {
            handleShortOrder(context, sessionID);
        }
    }

    /**
     * Handle short order by initiating locate request workflow.
     */
    private void handleShortOrder(ExecutionReportContext context, SessionID sessionID) {
        log.info("Processing short sell order: ClOrdID={}, Symbol={}, Qty={}",
                context.getClOrdID(), context.getSymbol(), context.getOrderQty());

        // Build Order object from ExecutionReportContext instead of querying DB
        // This avoids race conditions where the order might not be persisted yet
        Order order = buildOrderFromContext(context);

        // Create draft orders for shadow accounts before sending locate request
        List<Order> draftOrders = shortOrderDraftService.createDraftOrdersForShadowAccounts(order);
        log.info("Created {} draft orders for shadow accounts", draftOrders.size());

        // Process the short order to send locate request
        shortOrderProcessingService.processShortOrder(
                order,
                context.getSymbol(),
                context.getOrderQty(),
                sessionID
        );
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
            // We need an Account entity, try to find it or create a placeholder
            // For locate request purposes, we mainly need the account number
            Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
            if (accountOpt.isPresent()) {
                order.setAccount(accountOpt.get());
            } else {
                // Fallback: create a transient account object with just the number
                // This might cause issues if we try to persist 'order' without persisting 'account'
                // But processShortOrder creates a LocateRequest which refers to Order.
                // If Order is transient, LocateRequest persistence will fail unless we cascade or handle it.
                // Ideally, we should ensure the account exists.
                log.warn("Account not found for order: {}", context.getAccount());
                Account account = new Account();
                account.setAccountNumber(context.getAccount());
                order.setAccount(account);
            }
        }

        return order;
    }
}