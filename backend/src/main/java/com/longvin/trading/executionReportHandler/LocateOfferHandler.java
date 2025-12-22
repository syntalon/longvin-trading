package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.repository.LocateRequestRepository;
import com.longvin.trading.service.LocateDecisionService;
import com.longvin.trading.service.LocateRouteService;
import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.Optional;

/**
 * Handler for all Locate-related ExecutionReports (OrdStatus=B).
 * 
 * Handles two scenarios:
 * 1. Quote Protocol (Route Type 0/2): After we send Accept, broker confirms with OrdStatus=B
 *    - ClOrdID matches our QuoteReqID (starts with "LOCATE_" or found in LocateRequest table)
 *    - Action: Trigger shadow order placement
 * 
 * 2. Offer Protocol (Route Type 1): Broker sends OrdStatus=B as Locate Offer
 *    - This is a new offer we haven't seen before
 *    - Action: Decide to Accept or Reject the offer
 */
@Component
public class LocateOfferHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(LocateOfferHandler.class);
    
    private final FixMessageSender fixMessageSender;
    private final LocateDecisionService locateDecisionService;
    private final LocateRouteService locateRouteService;
    private final LocateRequestRepository locateRequestRepository;
    private final ShortOrderProcessingService shortOrderProcessingService;

    public LocateOfferHandler(FixMessageSender fixMessageSender,
                              LocateDecisionService locateDecisionService,
                              LocateRouteService locateRouteService,
                              LocateRequestRepository locateRequestRepository,
                              ShortOrderProcessingService shortOrderProcessingService) {
        this.fixMessageSender = fixMessageSender;
        this.locateDecisionService = locateDecisionService;
        this.locateRouteService = locateRouteService;
        this.locateRequestRepository = locateRequestRepository;
        this.shortOrderProcessingService = shortOrderProcessingService;
    }


    @Override
    public boolean supports(ExecutionReportContext context) {
        // OrdStatus=B ('Calculated') is used for Locate in DAS
        return context.getOrdStatus() == 'B';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("Received OrdStatus=B (Locate): OrderID={}, ClOrdID={}, Symbol={}, Qty={}, Side={} ({})",
                context.getOrderID(), context.getClOrdID(), context.getSymbol(), 
                context.getOrderQty(), context.getSide(), 
                context.isShortOrder() ? "Short" : "Regular");

        // Determine if this is Quote Protocol Confirmation or Offer Protocol
        String clOrdId = context.getClOrdID();
        
        // Check if this is a Quote Protocol confirmation (we initiated the locate request)
        Optional<LocateRequest> locateRequestOpt = findLocateRequestByClOrdId(clOrdId);
        
        if (locateRequestOpt.isPresent()) {
            // Quote Protocol: This is a confirmation after we sent Accept
            handleQuoteProtocolConfirmation(context, sessionID, locateRequestOpt.get());
        } else {
            // Offer Protocol: This is a new locate offer from broker
            handleOfferProtocol(context, sessionID);
        }
    }

    /**
     * Find LocateRequest by ClOrdID (which should match our QuoteReqID).
     */
    private Optional<LocateRequest> findLocateRequestByClOrdId(String clOrdId) {
        if (clOrdId == null || clOrdId.isBlank()) {
            return Optional.empty();
        }
        
        // Try to find by fixQuoteReqId
        return locateRequestRepository.findByFixQuoteReqId(clOrdId);
    }

    /**
     * Handle Quote Protocol (Route Type 0/2) Confirmation.
     * After we sent Accept, broker confirms the locate with OrdStatus=B.
     * Now we can proceed to place shadow orders.
     */
    private void handleQuoteProtocolConfirmation(ExecutionReportContext context, 
                                                  SessionID sessionID, 
                                                  LocateRequest locateRequest) {
        log.info("Quote Protocol Confirmation: QuoteReqID={}, Symbol={}, Status={}",
                locateRequest.getFixQuoteReqId(), locateRequest.getSymbol(), locateRequest.getStatus());

        // Verify the locate was approved
        if (locateRequest.getStatus() != LocateRequest.LocateStatus.APPROVED_FULL &&
            locateRequest.getStatus() != LocateRequest.LocateStatus.APPROVED_PARTIAL) {
            log.warn("Received confirmation for non-approved locate request: QuoteReqID={}, Status={}",
                    locateRequest.getFixQuoteReqId(), locateRequest.getStatus());
            return;
        }

        // Get the primary order ID from locate request
        if (locateRequest.getOrder() == null) {
            log.error("LocateRequest has no associated order: QuoteReqID={}", locateRequest.getFixQuoteReqId());
            return;
        }

        String primaryOrderId = locateRequest.getOrder().getFixOrderId();
        if (primaryOrderId == null) {
            log.error("Primary order has no fixOrderId: LocateRequest={}", locateRequest.getFixQuoteReqId());
            return;
        }

        log.info("Locate confirmed, triggering shadow order placement: PrimaryOrderId={}, ApprovedQty={}",
                primaryOrderId, locateRequest.getApprovedQty());

        // Complete locate workflow: update coordinator and place shadow orders
        try {
            shortOrderProcessingService.processLocateConfirmationByQuoteReqId(
                    locateRequest.getFixQuoteReqId(),
                    sessionID,
                    "OrdStatus=B confirmation received"
            );
            log.info("Successfully completed locate confirmation workflow for QuoteReqID={} / PrimaryOrderId={}",
                    locateRequest.getFixQuoteReqId(), primaryOrderId);
        } catch (Exception e) {
            log.error("Error completing locate confirmation for PrimaryOrderId={}: {}", primaryOrderId, e.getMessage(), e);
        }
    }

    /**
     * Handle Offer Protocol (Route Type 1).
     * Broker is offering locate shares, we need to decide Accept or Reject.
     */
    private void handleOfferProtocol(ExecutionReportContext context, SessionID sessionID) {
        log.info("Offer Protocol: Received locate offer. OrderID={}, Symbol={}, Qty={}",
                context.getOrderID(), context.getSymbol(), context.getOrderQty());

        // Only process for short orders
        if (!context.isShortOrder()) {
            log.debug("Non-short order with OrdStatus=B, skipping: ClOrdID={}", context.getClOrdID());
            return;
        }

        // Decide whether to accept or reject the offer
        boolean shouldAccept = locateDecisionService.shouldAcceptLocateOffer(context);

        if (shouldAccept) {
            // Accept the offer
            fixMessageSender.sendShortLocateAcceptOffer(sessionID, context.getOrderID());
            log.info("Accepted locate offer for OrderID={}, Symbol={}, Qty={}",
                    context.getOrderID(), context.getSymbol(), context.getOrderQty());
        } else {
            // Reject and request new quote
            fixMessageSender.sendShortLocateRejectOffer(sessionID, context.getOrderID());
            log.info("Rejected locate offer for OrderID={}", context.getOrderID());

            // Request a new locate quote with alternative route
            requestNewLocateQuote(context, sessionID);
        }
    }

    /**
     * Request a new locate quote after rejecting an offer.
     */
    private void requestNewLocateQuote(ExecutionReportContext context, SessionID sessionID) {
        String quoteReqID = "QL_" + context.getOrderID() + "_NEW_" + System.currentTimeMillis();
        String locateRoute = locateRouteService.getAvailableLocateRoute(context.getSymbol());

        if (locateRoute == null) {
            log.error("No available locate route for symbol: {}", context.getSymbol());
            return;
        }

        fixMessageSender.sendShortLocateQuoteRequest(
                sessionID,
                context.getSymbol(),
                context.getOrderQtyAsInt(),
                context.getAccount(),
                locateRoute,
                quoteReqID
        );

        log.info("Requested new locate quote after rejection. QuoteReqID={}, Route={}", 
                quoteReqID, locateRoute);
    }
}