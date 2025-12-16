package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.service.LocateDecisionService;
import com.longvin.trading.service.LocateRouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

@Component
public class LocateOfferHandler implements ExecutionReportHandler {
    private static final Logger log = LoggerFactory.getLogger(LocateOfferHandler.class);
    private final FixMessageSender fixMessageSender;
    private final LocateDecisionService locateDecisionService;
    private final LocateRouteService locateRouteService; // 添加这个依赖

    public LocateOfferHandler(FixMessageSender fixMessageSender,
                              LocateDecisionService locateDecisionService,
                              LocateRouteService locateRouteService) {
        this.fixMessageSender = fixMessageSender;
        this.locateDecisionService = locateDecisionService;
        this.locateRouteService = locateRouteService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getOrdStatus() == 'B' && context.isShortOrder();
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("Received locate offer. OrderID: {}, ClOrdID: {}",
                context.getOrderID(), context.getClOrdID());

        boolean shouldAccept = locateDecisionService.shouldAcceptLocateOffer(context);

        if (shouldAccept) {
            fixMessageSender.sendShortLocateAcceptOffer(sessionID, context.getOrderID());
            log.info("Accepted locate offer for OrderID: {}", context.getOrderID());

            //updateLocalOrderStatus(context.getClOrdID(), "LOCATE_ACCEPTED");
        } else {
            //fixMessageSender.sendShortLocateRejectOffer(sessionID, context.getOrderID());
            log.info("Rejected locate offer for OrderID: {}", context.getOrderID());

            requestNewLocateQuote(context, sessionID);
        }
    }

    private void requestNewLocateQuote(ExecutionReportContext context, SessionID sessionID) {
        String quoteReqID = "QL_" + context.getOrderID() + "_NEW_" + System.currentTimeMillis();
        String locateRoute = locateRouteService.getAvailableLocateRoute(context.getSymbol());

        fixMessageSender.sendShortLocateQuoteRequest(
                sessionID,
                context.getSymbol(),
                context.getOrderQty(),
                context.getAccount(),
                locateRoute,
                quoteReqID
        );

        log.info("Requested new locate quote after rejection. QuoteReqID: {}", quoteReqID);
    }
}