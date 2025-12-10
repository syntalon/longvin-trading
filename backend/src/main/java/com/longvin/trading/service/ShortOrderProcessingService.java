package com.longvin.trading.service;

import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.repository.LocateRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service for processing short orders with locate requests and stock borrowing.
 * When a short order is detected for the primary account, this service:
 * 1. Sends a locate request to check stock availability
 * 2. Waits for locate response
 * 3. Borrows the stock if available
 * 4. Places the order for shadow accounts
 */
@Service
public class ShortOrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ShortOrderProcessingService.class);

    // FIX Side values for short orders
    private static final char SIDE_SELL_SHORT = '5';
    private static final char SIDE_SELL_SHORT_EXEMPT = '6';

    private final LocateRequestRepository locateRequestRepository;
    private final ShortLocateCoordinator shortLocateCoordinator;
    private final ShortOrderPlacementService placementService;

    public ShortOrderProcessingService(LocateRequestRepository locateRequestRepository,
                                       ShortLocateCoordinator shortLocateCoordinator,
                                       ShortOrderPlacementService placementService) {
        this.locateRequestRepository = locateRequestRepository;
        this.shortLocateCoordinator = shortLocateCoordinator;
        this.placementService = placementService;
    }

    /**
     * Check if an order is a short order (SELL_SHORT or SELL_SHORT_EXEMPT).
     */
    public boolean isShortOrder(char side) {
        return side == SIDE_SELL_SHORT || side == SIDE_SELL_SHORT_EXEMPT;
    }

    /**
     * Process a new short order execution report.
     * This will trigger the locate request workflow.
     * 
     * @param order The order entity (primary account order)
     * @param symbol Symbol to locate
     * @param quantity Quantity to locate
     * @param initiatorSessionID Session to send locate request
     * @return LocateRequest entity created and persisted
     */
    @Transactional
    public LocateRequest processShortOrder(Order order, String symbol, BigDecimal quantity, SessionID initiatorSessionID) {
        log.info("Processing short order for symbol={}, quantity={}, orderId={}", 
            symbol, quantity, order.getId());

        // Create locate request entity
        // Generate QuoteReqID (tag 131) - format: LOCATE_SYMBOL_GROUPID
        String quoteReqId = String.format("LOCATE_%s_%s", symbol, 
            order.getOrderGroup() != null ? order.getOrderGroup().getId().toString().substring(0, 8) 
            : UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        
        LocateRequest locateRequest = LocateRequest.builder()
            .order(order)
            .orderGroup(order.getOrderGroup() != null ? order.getOrderGroup() : null)
            .account(order.getAccount())
            .symbol(symbol)
            .quantity(quantity)
            .status(LocateRequest.LocateStatus.PENDING)
            .fixQuoteReqId(quoteReqId)
            .build();

        // Persist locate request
        locateRequest = locateRequestRepository.save(locateRequest);

        // Send locate request via FIX
        sendLocateRequest(locateRequest, initiatorSessionID);

        return locateRequest;
    }

    /**
     * Send a FIX LocateRequest message.
     * Note: FIX 4.2 may not have standard LocateRequest. This is a placeholder
     * that can be adapted to your broker's specific locate request format.
     * 
     * @param locateRequest The locate request to send
     * @param initiatorSessionID The session to send on
     * @param retryCount Current retry attempt (0 = first attempt)
     * @return true if sent successfully, false otherwise
     */
    @Transactional
    private boolean sendLocateRequest(LocateRequest locateRequest, SessionID initiatorSessionID, int retryCount) {
        try {
            Session session = Session.lookupSession(initiatorSessionID);
            if (session == null || !session.isLoggedOn()) {
                log.error("Cannot send locate request: session {} is not logged on (retry {})",
                    initiatorSessionID, retryCount);

                if (retryCount < 3) {
                    log.info("Will retry locate request in 5 seconds (attempt {})", retryCount + 1);
                    // Schedule retry (in a real implementation, use a scheduler)
                    return false;
                } else {
                    locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
                    locateRequest.setResponseMessage("Session not logged on after " + (retryCount + 1) + " attempts");
                    locateRequestRepository.save(locateRequest);
                    notifyLocateFailure(locateRequest, "Session not logged on");
                    return false;
                }
            }

            // Create Short Locate Quote Request (MsgType=R) per DAS spec
            // Tag 131 = QuoteReqID (our ID, echoed back in response)
            // Tag 55 = Symbol
            // Tag 38 = OrderQty (requested locate size)
            // Tag 1 = Account
            // Tag 100 = ExDestination (locate route)
            Message locateMsg = new Message();
            locateMsg.getHeader().setString(MsgType.FIELD, "R"); // Short Locate Quote Request
            
            // Tag 131: QuoteReqID (our request ID, will be echoed back)
            if (locateRequest.getFixQuoteReqId() != null) {
                locateMsg.setString(quickfix.field.QuoteReqID.FIELD, locateRequest.getFixQuoteReqId());
            } else {
                // Fallback: use ClOrdID if QuoteReqID not set
                locateMsg.setString(ClOrdID.FIELD, locateRequest.getFixQuoteReqId() != null 
                    ? locateRequest.getFixQuoteReqId() 
                    : "LOC-" + System.currentTimeMillis());
            }
            
            locateMsg.setString(Symbol.FIELD, locateRequest.getSymbol());
            locateMsg.setDouble(OrderQty.FIELD, locateRequest.getQuantity().doubleValue());
            locateMsg.setString(quickfix.field.Account.FIELD, locateRequest.getAccount().getAccountNumber());
            
            // Tag 100: ExDestination (locate route) - if configured
            if (locateRequest.getLocateRoute() != null && !locateRequest.getLocateRoute().isEmpty()) {
                locateMsg.setString(ExDestination.FIELD, locateRequest.getLocateRoute());
            }

            session.send(locateMsg);
            log.info("Sent Short Locate Quote Request (MsgType=R): QuoteReqID={}, Symbol={}, Qty={}, Route={}, Retry={}", 
                locateRequest.getFixQuoteReqId(), locateRequest.getSymbol(), locateRequest.getQuantity(), 
                locateRequest.getLocateRoute(), retryCount);
            
            return true;

        } catch (Exception e) {
            log.error("Error sending locate request for order {} (retry {}): {}",
                locateRequest.getOrder().getId(), retryCount, e.getMessage(), e);

            if (retryCount < 3) {
                log.info("Will retry locate request in 5 seconds (attempt {})", retryCount + 1);
                return false; // Will retry
            } else {
                locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
                locateRequest.setResponseMessage("Error sending locate request after " + (retryCount + 1) + " attempts: " + e.getMessage());
                locateRequestRepository.save(locateRequest);
                notifyLocateFailure(locateRequest, "Error sending locate request: " + e.getMessage());
                return false;
            }
        }
    }
    
    /**
     * Send locate request (first attempt).
     */
    private void sendLocateRequest(LocateRequest locateRequest, SessionID initiatorSessionID) {
        sendLocateRequest(locateRequest, initiatorSessionID, 0);
    }

    /**
     * Process a locate response (when broker responds to locate request).
     * If approved, borrow the stock and place orders for shadow accounts.
     * 
     * @param locateRequestId The locate request ID that was responded to
     * @param approved Whether locate was approved
     * @param availableQty Quantity available for borrowing
     * @param locateId Locate ID from broker
     * @param responseMessage Response message
     * @param initiatorSessionID Session to send orders
     */
    @Transactional
    public void processLocateResponse(UUID locateRequestId,
                                     boolean approved,
                                     BigDecimal offerPx,
                                     BigDecimal offerSize,
                                     String responseMessage,
                                     SessionID initiatorSessionID) {
        LocateRequest locateRequest = locateRequestRepository.findById(locateRequestId)
            .orElseThrow(() -> new IllegalArgumentException("LocateRequest not found: " + locateRequestId));
        
        // Check if already processed
        if (locateRequest.getStatus() != LocateRequest.LocateStatus.PENDING) {
            log.warn("Locate request {} already processed with status {}, ignoring response", 
                locateRequest.getFixQuoteReqId(), locateRequest.getStatus());
            return;
        }
        
        log.info("Processing Short Locate Quote Response: QuoteReqID={}, Approved={}, OfferPx={}, OfferSize={}", 
            locateRequest.getFixQuoteReqId(), approved, offerPx, offerSize);

        if (!approved) {
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage(responseMessage != null ? responseMessage : "Locate request rejected");
            locateRequestRepository.save(locateRequest);
            log.warn("Locate request rejected: {}", responseMessage);
            notifyLocateFailure(locateRequest, responseMessage != null ? responseMessage : "Locate rejected");
            return;
        }

        // Update locate request with quote response data
        locateRequest.setOfferPx(offerPx);
        locateRequest.setOfferSize(offerSize);
        if (responseMessage != null) {
            locateRequest.setResponseMessage(responseMessage);
        }

        if (!approved || offerSize == null || offerSize.compareTo(BigDecimal.ZERO) <= 0) {
            // Locate rejected: OfferSize <= 0
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setApprovedQty(BigDecimal.ZERO);
            locateRequestRepository.save(locateRequest);
            log.warn("Locate quote rejected: OfferSize={}, Message={}", offerSize, responseMessage);
            notifyLocateFailure(locateRequest, responseMessage != null ? responseMessage : "Locate rejected (OfferSize <= 0)");
            return;
        }

        // Determine if full or partial approval
        boolean isFullApproval = offerSize.compareTo(locateRequest.getQuantity()) >= 0;
        BigDecimal approvedQty = offerSize.compareTo(locateRequest.getQuantity()) > 0 
            ? locateRequest.getQuantity() 
            : offerSize;

        try {
            // Update locate request status
            if (isFullApproval) {
                locateRequest.setStatus(LocateRequest.LocateStatus.APPROVED_FULL);
            } else {
                locateRequest.setStatus(LocateRequest.LocateStatus.APPROVED_PARTIAL);
            }
            locateRequest.setApprovedQty(approvedQty);
            locateRequestRepository.save(locateRequest);

            // Notify success and trigger shadow order placement
            notifyLocateSuccess(locateRequest, approvedQty, offerPx, responseMessage, initiatorSessionID);
        } catch (Exception e) {
            log.error("Error processing locate response for QuoteReqID={}", locateRequest.getFixQuoteReqId(), e);
            // Update status to indicate error
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage("Error processing locate response: " + e.getMessage());
            locateRequestRepository.save(locateRequest);
            shortLocateCoordinator.completeExceptionally(resolvePrimaryOrderId(locateRequest), e);
        }
    }
    
    /**
     * Process locate response by QuoteReqID (tag 131).
     */
    @Transactional
    public void processLocateResponseByQuoteReqId(String fixQuoteReqId,
                                                 BigDecimal offerPx,
                                                 BigDecimal offerSize,
                                                 String responseMessage,
                                                 SessionID initiatorSessionID) {
        LocateRequest locateRequest = locateRequestRepository.findByFixQuoteReqId(fixQuoteReqId)
            .orElseThrow(() -> new IllegalArgumentException("LocateRequest not found: " + fixQuoteReqId));
        
        // Determine approval status: offerSize > 0 means approved
        boolean approved = offerSize != null && offerSize.compareTo(BigDecimal.ZERO) > 0;
        
        processLocateResponse(locateRequest.getId(), approved, offerPx, offerSize, responseMessage, initiatorSessionID);
    }


    /**
     * Notify locate failure to the coordinator.
     */
    private void notifyLocateFailure(LocateRequest locateRequest, String message) {
        String primaryOrderId = resolvePrimaryOrderId(locateRequest);
        if (primaryOrderId != null) {
            shortLocateCoordinator.completeFailure(primaryOrderId, message);
        }
    }

    /**
     * Notify locate success to the coordinator and trigger shadow order placement.
     */
    private void notifyLocateSuccess(LocateRequest locateRequest, BigDecimal approvedQty, BigDecimal offerPx,
                                    String message, SessionID initiatorSessionID) {
        String primaryOrderId = resolvePrimaryOrderId(locateRequest);
        if (primaryOrderId != null) {
            // Use QuoteReqID as locateId for coordinator
            shortLocateCoordinator.completeSuccess(primaryOrderId, approvedQty, locateRequest.getFixQuoteReqId(), message);
            
            // Trigger shadow order placement after locate success
            placementService.onLocateSuccess(primaryOrderId, approvedQty, locateRequest.getFixQuoteReqId(), initiatorSessionID);
        }
    }

    /**
     * Resolve the primary order ID from the locate request.
     */
    private String resolvePrimaryOrderId(LocateRequest locateRequest) {
        Order order = locateRequest.getOrder();
        return order != null ? order.getFixOrderId() : null;
    }
}
