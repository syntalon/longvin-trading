package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.OrderService;
import com.longvin.trading.service.ShortOrderProcessingService;
import com.longvin.trading.util.QuoteReqIdMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handler for Short Locate Quote Response (MsgType=S, section 3.12).
 * 
 * This message is sent by the broker in response to a Short Locate Quote Request (MsgType=R, section 3.11).
 * It contains the offer price and size for the requested stock.
 * 
 * For Type 0 routes:
 * 1. FillOrderHandler sends Short Locate Quote Request (3.11) with a short QuoteReqID (<=39 chars per DAS).
 * 2. This handler receives Short Locate Quote Response (3.12)
 * 3. This handler sends locate order (3.14) to the shadow account
 * 4. ExecutionReport (3.5) received → Route 0 locate copy completed
 */
@Component
public class LocateResponseHandler implements ExecutionReportHandler {

    private static final Logger log = LoggerFactory.getLogger(LocateResponseHandler.class);

    private final ShortOrderProcessingService shortOrderProcessingService;
    private final FixMessageSender fixMessageSender;
    private final OrderService orderService;
    private final AccountCacheService accountCacheService;

    public LocateResponseHandler(ShortOrderProcessingService shortOrderProcessingService,
                                 FixMessageSender fixMessageSender,
                                 OrderService orderService,
                                 AccountCacheService accountCacheService) {
        this.shortOrderProcessingService = shortOrderProcessingService;
        this.fixMessageSender = fixMessageSender;
        this.orderService = orderService;
        this.accountCacheService = accountCacheService;
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

        // Resolve context: prefer lookup (short ID <=39 chars), fallback to embedded format
        String shadowAccount = null;
        String primaryClOrdId = null;
        String locateRoute = null;
        var ctxOpt = QuoteReqIdMapper.lookupAndRemove(quoteReqID);
        if (ctxOpt.isPresent()) {
            var ctx = ctxOpt.get();
            shadowAccount = ctx.shadowAccount();
            primaryClOrdId = ctx.primaryClOrdId();
            locateRoute = ctx.locateRoute();
            log.info("Resolved QuoteReqID from mapping: shadowAccount={}, primaryClOrdId={}, route={}", 
                    shadowAccount, primaryClOrdId, locateRoute);
        } else if (quoteReqID != null && quoteReqID.startsWith("QL_")) {
            String[] parts = quoteReqID.split("_");
            log.debug("Parsing legacy QuoteReqID: original={}, parts.length={}, parts={}", 
                    quoteReqID, parts.length, String.join(", ", parts));
            // Skip if this looks like short format (QL_{base36}_{4chars}) - mapping was lost
            boolean looksLikeShortFormat = parts.length == 3 && parts[2].length() == 4 
                    && parts[1].length() <= 12 && parts[1].matches("[a-z0-9]+");
            if (looksLikeShortFormat) {
                log.warn("QuoteReqID looks like short format but mapping not found (cache lost?): QuoteReqID={}", quoteReqID);
            } else if (parts.length >= 4) {
                // Legacy embedded format: QL_{shadowAccount}_{primaryClOrdId}_{route}_{timestamp}
                shadowAccount = parts[1];
                primaryClOrdId = parts[2];
                locateRoute = parts[3];
                log.info("Parsed legacy QuoteReqID: shadowAccount={}, primaryClOrdId={}, route={}", 
                        shadowAccount, primaryClOrdId, locateRoute);
            } else if (parts.length >= 3) {
                shadowAccount = parts[1];
                primaryClOrdId = parts[2];
                log.info("Parsed legacy QuoteReqID (no route): shadowAccount={}, primaryClOrdId={}", 
                        shadowAccount, primaryClOrdId);
            } else {
                log.warn("QuoteReqID format unexpected: QuoteReqID={}, parts.length={}", quoteReqID, parts.length);
            }
        } else {
            log.warn("QuoteReqID not found in mapping and does not start with 'QL_': QuoteReqID={}", quoteReqID);
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
        
        // Find shadow account
        Optional<Account> shadowAccountOpt = accountCacheService.findByAccountNumber(shadowAccount);
        if (shadowAccountOpt.isEmpty()) {
            log.error("Shadow account not found: {}, cannot create locate order. QuoteReqID={}", 
                    shadowAccount, quoteReqID);
            return;
        }
        Account shadowAccountEntity = shadowAccountOpt.get();
        
        // Calculate order quantity
        BigDecimal orderQty = context.getOfferSize() != null ? context.getOfferSize() : BigDecimal.valueOf(100);
        
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", locateClOrdId);
        orderParams.put("side", '1'); // BUY for locate orders
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", orderQty.intValue());
        orderParams.put("account", shadowAccount);
        orderParams.put("exDestination", locateRoute); // Locate route
        orderParams.put("ordType", '1'); // MARKET
        orderParams.put("timeInForce", '0'); // DAY

        try {
            // Send order first
            fixMessageSender.sendNewOrderSingle(sessionID, orderParams);
            
            // Create shadow order with "Staged" event asynchronously (non-blocking)
            orderService.createShadowOrderWithStagedEventAsync(
                    primaryClOrdId, // Primary order ClOrdID
                    shadowAccountEntity,
                    locateClOrdId, // Shadow order ClOrdID
                    context.getSymbol(),
                    '1', // BUY for locate orders
                    '1', // MARKET
                    orderQty,
                    null, // No price for market orders
                    null, // No stop price
                    '0', // DAY
                    locateRoute,
                    sessionID
            );
            
            log.info("Locate order (3.14) sent to shadow account {}: ClOrdID={}, QuoteReqID={}, Symbol={}, Route={}",
                    shadowAccount, locateClOrdId, quoteReqID, context.getSymbol(), locateRoute);
        } catch (Exception e) {
            log.error("Error sending locate order (3.14) to shadow account {}: QuoteReqID={}, Error={}",
                    shadowAccount, quoteReqID, e.getMessage(), e);
        }
    }
}
