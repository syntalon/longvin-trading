package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.Map;

/**
 * Handler for Short Locate Quote Response (MsgType=S, section 3.12).
 * 
 * This message is sent by the broker in response to a Short Locate Quote Request (MsgType=R, section 3.11).
 * It contains the offer price and size for the requested stock.
 * 
 * For Type 0 routes:
 * 1. FillOrderHandler sends Short Locate Quote Request (3.11) with QuoteReqID format: QL_{shadowAccount}_{primaryClOrdId}_{timestamp}
 * 2. This handler receives Short Locate Quote Response (3.12)
 * 3. This handler sends locate order (3.14) to the shadow account
 * 4. ExecutionReport (3.5) received → Route 0 locate copy completed
 */
@Component
public class LocateResponseHandler implements ExecutionReportHandler {

    private static final Logger log = LoggerFactory.getLogger(LocateResponseHandler.class);

    private final ShortOrderProcessingService shortOrderProcessingService;
    private final FixMessageSender fixMessageSender;

    public LocateResponseHandler(ShortOrderProcessingService shortOrderProcessingService,
                                 FixMessageSender fixMessageSender) {
        this.shortOrderProcessingService = shortOrderProcessingService;
        this.fixMessageSender = fixMessageSender;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.isQuoteResponse();
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        String quoteReqID = context.getQuoteReqID();
        log.info("Received Short Locate Quote Response (MsgType=S, section 3.12): QuoteReqID={}, Symbol={}, OfferPx={}, OfferSize={}",
            quoteReqID, context.getSymbol(), context.getOfferPx(), context.getOfferSize());

        // Parse QuoteReqID to extract shadow account info and route
        // Format: QL_{shadowAccount}_{primaryClOrdId}_{route}_{timestamp}
        String shadowAccount = null;
        String primaryClOrdId = null;
        String locateRoute = null;
        if (quoteReqID != null && quoteReqID.startsWith("QL_")) {
            String[] parts = quoteReqID.split("_");
            if (parts.length >= 4) {
                shadowAccount = parts[1];
                primaryClOrdId = parts[2];
                locateRoute = parts[3];
                log.debug("Parsed QuoteReqID: shadowAccount={}, primaryClOrdId={}, route={}", 
                        shadowAccount, primaryClOrdId, locateRoute);
            } else if (parts.length >= 3) {
                // Fallback for old format without route
                shadowAccount = parts[1];
                primaryClOrdId = parts[2];
                log.debug("Parsed QuoteReqID (old format): shadowAccount={}, primaryClOrdId={}", 
                        shadowAccount, primaryClOrdId);
            }
        }
        
        // Use ExDestination from context if available, otherwise use parsed route
        if (locateRoute == null || locateRoute.isBlank()) {
            locateRoute = context.getExDestination();
        }

        if (shadowAccount == null || shadowAccount.isBlank()) {
            log.warn("Cannot extract shadow account from QuoteReqID: {}, delegating to ShortOrderProcessingService", quoteReqID);
            // Fallback to existing service
            shortOrderProcessingService.processLocateResponseByQuoteReqId(
                quoteReqID,
                context.getOfferPx(),
                context.getOfferSize(),
                context.getText(),
                sessionID
            );
            return;
        }

        // Validate we have the route
        if (locateRoute == null || locateRoute.isBlank()) {
            log.error("Cannot determine locate route for QuoteReqID: {}, cannot send locate order", quoteReqID);
            return;
        }

        // Send locate order (section 3.14) to shadow account
        // This is a BUY order with ExDestination set to the locate route
        log.info("Sending locate order (3.14) to shadow account {} after receiving quote response: QuoteReqID={}, Symbol={}, Qty={}, Route={}",
                shadowAccount, quoteReqID, context.getSymbol(), context.getOfferSize(), locateRoute);

        String locateClOrdId = "COPY-" + shadowAccount + "-" + primaryClOrdId;
        
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", locateClOrdId);
        orderParams.put("side", '1'); // BUY for locate orders
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", context.getOfferSize() != null ? context.getOfferSize().intValue() : 100); // Use offer size or default
        orderParams.put("account", shadowAccount);
        orderParams.put("exDestination", locateRoute); // Locate route
        orderParams.put("ordType", '1'); // MARKET
        orderParams.put("timeInForce", '0'); // DAY

        try {
            fixMessageSender.sendNewOrderSingle(sessionID, orderParams);
            log.info("Locate order (3.14) sent to shadow account {}: ClOrdID={}, QuoteReqID={}, Symbol={}, Route={}",
                    shadowAccount, locateClOrdId, quoteReqID, context.getSymbol(), locateRoute);
        } catch (Exception e) {
            log.error("Error sending locate order (3.14) to shadow account {}: QuoteReqID={}, Error={}",
                    shadowAccount, quoteReqID, e.getMessage(), e);
        }
    }
}
