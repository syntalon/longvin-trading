package com.longvin.trading.service;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;


@Service
public class LocateDecisionService {

    private static final Logger log = LoggerFactory.getLogger(LocateDecisionService.class);


    public boolean shouldAcceptLocateOffer(ExecutionReportContext context) {
        if (context.getLeavesQty().compareTo(context.getOrderQty()) < 0) {
            log.info("Rejecting locate offer - insufficient quantity. Required: {}, Available: {}",
                    context.getOrderQty(), context.getLeavesQty());
            return false;
        }


        BigDecimal price = context.getAvgPx() != null ? context.getAvgPx() : BigDecimal.ZERO;
        double estimatedCost = price.multiply(context.getOrderQty()).doubleValue();
        if (estimatedCost > getMaximumAcceptableCost(context)) {
            log.info("Rejecting locate offer - cost too high: {}", estimatedCost);
            return false;
        }


        if (!isAccountAllowedToBorrow(context.getAccount())) {
            log.info("Rejecting locate offer - account not allowed to borrow: {}", context.getAccount());
            return false;
        }


        log.info("Accepting locate offer for OrderID: {}, Quantity: {}",
                context.getOrderID(), context.getOrderQty());
        return true;
    }


    public String getAlternativeLocateRoute(String symbol) {
        Map<String, String> alternativeRoutes = new HashMap<>();
        alternativeRoutes.put("AAPL", "LOCATE_B");
        alternativeRoutes.put("MSFT", "LOCATE_Y");
        alternativeRoutes.put("GOOGL", "LOCATE_Q");

        return alternativeRoutes.get(symbol.toUpperCase());
    }

    private double getMaximumAcceptableCost(ExecutionReportContext context) {
        // Use context to determine max cost if needed, e.g. based on account balance
        return 1000000.0;
    }

    private boolean isAccountAllowedToBorrow(String account) {
        // Check account permissions
        return true;
    }
}