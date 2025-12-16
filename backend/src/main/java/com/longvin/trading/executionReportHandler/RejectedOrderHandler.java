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
        return context.getOrdStatus() == '8' ||
                (context.getText() != null &&
                        context.getText().toLowerCase().contains("locate"));
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.warn("Order rejected. ClOrdID: {}, Reason: {}",
                context.getClOrdID(), context.getText());

        if (isLocateIssue(context.getText())) {
            if (context.isShortOrder()) {

                requestShortLocate(context, sessionID);
            } else {
                log.warn("Non-short order rejected due to locate issue: {}", context.getText());
            }
        } else if (isRouteIssue(context.getText())) {
            retryWithAlternativeRoute(context, sessionID);
        } else {
            //updateLocalOrderStatus(context.getClOrdID(), "REJECTED");
            log.error("Order permanently rejected: {}", context.getText());
        }
    }

    private boolean isLocateIssue(String text) {
        return text != null && (
                text.toLowerCase().contains("locate") ||
                        text.toLowerCase().contains("shortable") ||
                        text.toLowerCase().contains("borrow") ||
                        text.toLowerCase().contains("no locate")
        );
    }

    private boolean isRouteIssue(String text) {
        return text != null && (
                text.toLowerCase().contains("route") ||
                        text.toLowerCase().contains("disable") ||
                        text.toLowerCase().contains("unavailable")
        );
    }

    private void requestShortLocate(ExecutionReportContext context, SessionID sessionID) {
        String locateRoute = locateRouteService.getAvailableLocateRoute(context.getSymbol());
        if (locateRoute != null) {
            String quoteReqID = "QL_" + context.getClOrdID() + "_" + System.currentTimeMillis();

            fixMessageSender.sendShortLocateQuoteRequest(
                    sessionID,
                    context.getSymbol(),
                    context.getOrderQty(),
                    context.getAccount(),
                    locateRoute,
                    quoteReqID
            );

            log.info("Requested short locate quote. QuoteReqID: {}, Symbol: {}",
                    quoteReqID, context.getSymbol());
        } else {
            log.error("No available locate route for symbol: {}", context.getSymbol());
        }
    }

    private void retryWithAlternativeRoute(ExecutionReportContext context, SessionID sessionID) {
        String alternativeRoute = locateRouteService.getAlternativeRoute(context.getExDestination());
        if (alternativeRoute != null) {
            String newClOrdID = context.getClOrdID() + "_R" + System.currentTimeMillis();

            Map<String, Object> orderParams = new HashMap<>();
            orderParams.put("clOrdID", newClOrdID);
            orderParams.put("symbol", context.getSymbol());
            orderParams.put("side", context.getSide());
            orderParams.put("orderQty", context.getOrderQty());
            orderParams.put("ordType", '2'); // LIMIT
            orderParams.put("price", context.getAvgPx()); // 使用上次的价格
            orderParams.put("timeInForce", '0'); // DAY
            orderParams.put("account", context.getAccount());
            orderParams.put("exDestination", alternativeRoute);

            fixMessageSender.sendNewOrderSingle(sessionID, orderParams);

            log.info("Retrying order with alternative route. Original: {}, New: {}, Route: {}",
                    context.getClOrdID(), newClOrdID, alternativeRoute);
        }
    }
}