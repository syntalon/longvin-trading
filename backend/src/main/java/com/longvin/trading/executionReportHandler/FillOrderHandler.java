package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.repository.OrderRepository;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.ShortOrderDraftService;
import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final ShortOrderProcessingService shortOrderProcessingService;
    private final ShortOrderDraftService shortOrderDraftService;
    private final AccountCacheService accountCacheService;
    private final FixMessageSender fixMessageSender;
    private final FixSessionRegistry fixSessionRegistry;

    public FillOrderHandler(OrderRepository orderRepository,
                           ShortOrderProcessingService shortOrderProcessingService,
                           ShortOrderDraftService shortOrderDraftService,
                           AccountCacheService accountCacheService,
                           FixMessageSender fixMessageSender,
                           FixSessionRegistry fixSessionRegistry) {
        this.orderRepository = orderRepository;
        this.shortOrderProcessingService = shortOrderProcessingService;
        this.shortOrderDraftService = shortOrderDraftService;
        this.accountCacheService = accountCacheService;
        this.fixMessageSender = fixMessageSender;
        this.fixSessionRegistry = fixSessionRegistry;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        // Handle both partial fills (1) and full fills (2)
        return context.getExecType() == '1' || context.getExecType() == '2';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        if (context.getExecType() == '2') {
            log.info("Order completely filled. ClOrdID: {}, AvgPx: {}",
                    context.getClOrdID(), context.getAvgPx());
        } else {
            log.info("Order partially filled. ClOrdID: {}, CumQty: {}/{}, LastPx: {}",
                    context.getClOrdID(),
                    context.getCumQty(), context.getOrderQty(),
                    context.getAvgPx());
        }
        
        // Try to find the order from database to check account type
        Order order = findOrderFromContext(context);
        boolean isPrimaryAccountOrder = order != null && order.getAccount() != null 
                && order.getAccount().getAccountType() == AccountType.PRIMARY;
        
        // For short sell orders, replicate to shadow accounts when filled
        if (context.isShortOrder()) {
            handleShortOrderReplication(context, sessionID);
        } else if (isPrimaryAccountOrder) {
            // For regular orders (buy/sell) from primary account, replicate to shadow accounts
            handleRegularOrderReplication(context, order);
        }
        
        recordFillInformation(context);
    }

    /**
     * Handle short order replication when order is filled.
     * Creates draft orders for shadow accounts and initiates locate request workflow.
     */
    private void handleShortOrderReplication(ExecutionReportContext context, SessionID sessionID) {
        log.info("Processing short sell order replication on fill: ClOrdID={}, Symbol={}, Qty={}",
                context.getClOrdID(), context.getSymbol(), context.getOrderQty());

        // Try to find the order from database first
        Order order = findOrderFromContext(context);
        if (order == null) {
            // If order not found in DB, build transient Order object
            order = buildOrderFromContext(context);
        }

        // Create draft orders for shadow accounts
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
     * Try to find the order from database by OrderID or ClOrdID.
     */
    private Order findOrderFromContext(ExecutionReportContext context) {
        if (context.getOrderID() != null) {
            Optional<Order> orderOpt = orderRepository.findByFixOrderId(context.getOrderID());
            if (orderOpt.isPresent()) {
                return orderOpt.get();
            }
        }
        if (context.getClOrdID() != null) {
            Optional<Order> orderOpt = orderRepository.findByFixClOrdId(context.getClOrdID());
            if (orderOpt.isPresent()) {
                return orderOpt.get();
            }
        }
        return null;
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
                log.warn("Account not found for order: {}", context.getAccount());
            }
        }

        return order;
    }

    /**
     * Handle regular order (buy/sell, not short) replication when order is filled.
     * Sends orders to shadow accounts.
     */
    private void handleRegularOrderReplication(ExecutionReportContext context, Order order) {
        log.info("Processing regular order replication on fill: ClOrdID={}, Symbol={}, Side={}, Qty={}",
                context.getClOrdID(), context.getSymbol(), context.getSide(), context.getOrderQty());

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate order to shadow accounts");
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();

        // Get shadow accounts
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccounts();
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found, cannot replicate order {}", context.getClOrdID());
            return;
        }

        // Build order from context if order entity is null
        if (order == null) {
            order = buildOrderFromContext(context);
        }

        // Send orders to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                sendOrderToShadowAccount(context, order, shadowAccount, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error replicating order to shadow account {}: {}", 
                        shadowAccount.getAccountNumber(), e.getMessage(), e);
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
        
        // Set time in force (default to DAY if not available)
        char timeInForce = primaryOrder.getTimeInForce() != null ? primaryOrder.getTimeInForce() : '0'; // DAY
        orderParams.put("timeInForce", timeInForce);
        
        // Set price if it's a limit order (FixMessageSender only supports price, not stopPx)
        if (primaryOrder.getPrice() != null && (ordType == '2' || ordType == '4')) { // LIMIT or STOP_LIMIT
            orderParams.put("price", primaryOrder.getPrice().doubleValue());
        }

        // Send order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        log.info("Replicated order to shadow account {}: ClOrdID={}, Symbol={}, Side={}, Qty={}",
                shadowAccountNumber, clOrdId, context.getSymbol(), context.getSide(), context.getOrderQty());
    }

    /**
     * Generate ClOrdID for shadow order.
     */
    private String generateShadowClOrdId(String primaryClOrdId, String shadowAccountNumber) {
        // Simple format: prefix + shadow account + original ClOrdID
        return "COPY-" + shadowAccountNumber + "-" + primaryClOrdId;
    }

    private void recordFillInformation(ExecutionReportContext context) {
        log.debug("Recording fill information for order: {}", context.getClOrdID());
    }
}