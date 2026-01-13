package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.service.LocateRouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.Map;

/**
 * Handler for rejected orders (OrdStatus=8).
 * 
 * Handles:
 * 1. Locate-related rejections: Retry with new locate request
 * 2. Route-related rejections: Retry with alternative route
 * 3. Other rejections: Log and mark order as rejected
 */
@Component
public class RejectedOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(RejectedOrderHandler.class);
    
    private final FixMessageSender fixMessageSender;
    private final LocateRouteService locateRouteService;

    public RejectedOrderHandler(FixMessageSender fixMessageSender,
                                LocateRouteService locateRouteService) {
        this.fixMessageSender = fixMessageSender;
        this.locateRouteService = locateRouteService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getOrdStatus() == '8'; // Rejected
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("Order rejected. ClOrdID={}, OrderID={}, Reason={}",
                context.getClOrdID(), context.getOrderID(), context.getText());

        // Check if this is a locate-related rejection
        if (context.getText() != null && context.getText().toLowerCase().contains("locate")) {
            handleLocateRejection(context, sessionID);
            return;
        }

        // Check if this is a route-related rejection
        if (context.getText() != null && context.getText().toLowerCase().contains("route")) {
            handleRouteRejection(context, sessionID);
            return;
        }

        // Handle permanent rejection
        handlePermanentRejection(context);
    }

    /**
     * Handle locate-related rejection - retry with new locate request.
     */
    private void handleLocateRejection(ExecutionReportContext context, SessionID sessionID) {
        log.info("Handling locate-related rejection. ClOrdID={}, Reason={}",
                context.getClOrdID(), context.getText());

        // TODO: Implement retry logic for locate rejection
        // This could involve:
        // 1. Creating a new locate request with alternative route
        // 2. Waiting for new locate approval
        // 3. Resending the order
        
        handlePermanentRejection(context); // For now, treat as permanent
    }

    /**
     * Handle route-related rejection - retry with alternative route.
     */
    private void handleRouteRejection(ExecutionReportContext context, SessionID sessionID) {
        log.info("Handling route-related rejection. ClOrdID={}, Reason={}",
                context.getClOrdID(), context.getText());

        // Validate required fields before proceeding
        if (context.getSymbol() == null || context.getSymbol().isBlank()) {
            log.error("Cannot retry order: Symbol is missing. ClOrdID={}", context.getClOrdID());
            handlePermanentRejection(context);
            return;
        }

        if (context.getSide() == 0) {
            log.error("Cannot retry order: Side is missing. ClOrdID={}", context.getClOrdID());
            handlePermanentRejection(context);
            return;
        }

        if (context.getAccount() == null || context.getAccount().isBlank()) {
            log.error("Cannot retry order: Account is missing. ClOrdID={}", context.getClOrdID());
            handlePermanentRejection(context);
            return;
        }

        String alternativeRoute = locateRouteService.getAvailableLocateRoute(context.getSymbol());
        if (alternativeRoute == null || alternativeRoute.isBlank()) {
            log.warn("No alternative route available for symbol: {}", context.getSymbol());
            handlePermanentRejection(context);
            return;
        }

        // Generate new ClOrdID for retry
        String newClOrdID = context.getClOrdID() + "_RETRY_" + System.currentTimeMillis();

        // Send new order with alternative route
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", newClOrdID);
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("side", context.getSide());
        orderParams.put("orderQty", context.getOrderQty() != null && context.getOrderQty().intValue() > 0 
                ? context.getOrderQty().intValue() : 1); // Default to 1 if missing
        orderParams.put("ordType", '2'); // LIMIT
        if (context.getAvgPx() != null && context.getAvgPx().doubleValue() > 0) {
            orderParams.put("price", context.getAvgPx().doubleValue());
        } else {
            // For LIMIT orders, we need a price. Use lastPx if available, otherwise log error
            if (context.getLastPx() != null && context.getLastPx().doubleValue() > 0) {
                orderParams.put("price", context.getLastPx().doubleValue());
                log.warn("Using LastPx as price for retry order. ClOrdID={}, Price={}", 
                        newClOrdID, context.getLastPx());
            } else {
                log.error("Cannot retry LIMIT order: No price available (AvgPx or LastPx). ClOrdID={}", 
                        context.getClOrdID());
                handlePermanentRejection(context);
                return;
            }
        }
        orderParams.put("timeInForce", '0'); // DAY
        orderParams.put("account", context.getAccount());
        orderParams.put("exDestination", alternativeRoute);

        try {
            fixMessageSender.sendNewOrderSingle(sessionID, orderParams);
            log.info("Retrying order with alternative route. Original ClOrdID={}, New ClOrdID={}, Route={}",
                    context.getClOrdID(), newClOrdID, alternativeRoute);
        } catch (Exception e) {
            log.error("Failed to retry order with alternative route. ClOrdID={}, Route={}, Error={}", 
                    context.getClOrdID(), alternativeRoute, e.getMessage(), e);
            handlePermanentRejection(context);
        }
    }

    /**
     * Handle permanent rejection - no retry possible.
     */
    private void handlePermanentRejection(ExecutionReportContext context) {
        log.error("Order permanently rejected. ClOrdID={}, OrderID={}, Reason={}",
                context.getClOrdID(), context.getOrderID(), context.getText());
        // TODO: Update order status in database to REJECTED
        // TODO: Send notification to user/strategy
    }
}