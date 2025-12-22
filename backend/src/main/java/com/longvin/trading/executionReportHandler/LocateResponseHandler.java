package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * Handler for Short Locate Quote Response (MsgType=S).
 * This message is sent by the broker in response to a Short Locate Quote Request (MsgType=R).
 * It contains the offer price and size for the requested stock.
 */
@Component
public class LocateResponseHandler implements ExecutionReportHandler {

    private static final Logger log = LoggerFactory.getLogger(LocateResponseHandler.class);

    private final ShortOrderProcessingService shortOrderProcessingService;

    public LocateResponseHandler(ShortOrderProcessingService shortOrderProcessingService) {
        this.shortOrderProcessingService = shortOrderProcessingService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.isQuoteResponse();
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("Received Short Locate Quote Response (MsgType=S): QuoteReqID={}, Symbol={}, OfferPx={}, OfferSize={}",
            context.getQuoteReqID(), context.getSymbol(), context.getOfferPx(), context.getOfferSize());

        // Process the response via service
        shortOrderProcessingService.processLocateResponseByQuoteReqId(
            context.getQuoteReqID(),
            context.getOfferPx(),
            context.getOfferSize(),
            context.getText(),
            sessionID
        );
    }
}
