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

        String alternativeRoute = locateRouteService.getAvailableLocateRoute(context.getSymbol());
        if (alternativeRoute == null) {
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
        orderParams.put("orderQty", context.getOrderQty());
        orderParams.put("ordType", '2'); // LIMIT
        orderParams.put("price", context.getAvgPx());
        orderParams.put("timeInForce", '0'); // DAY
        orderParams.put("account", context.getAccount());
        orderParams.put("exDestination", alternativeRoute);

        fixMessageSender.sendNewOrderSingle(sessionID, orderParams);

        log.info("Retrying order with alternative route. Original ClOrdID={}, New ClOrdID={}, Route={}",
                context.getClOrdID(), newClOrdID, alternativeRoute);
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