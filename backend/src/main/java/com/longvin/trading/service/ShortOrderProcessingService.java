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

    public ShortOrderProcessingService(LocateRequestRepository locateRequestRepository,
                                       ShortLocateCoordinator shortLocateCoordinator) {
        this.locateRequestRepository = locateRequestRepository;
        this.shortLocateCoordinator = shortLocateCoordinator;
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
        LocateRequest locateRequest = LocateRequest.builder()
            .order(order)
            .account(order.getAccount())
            .symbol(symbol)
            .quantity(quantity)
            .status(LocateRequest.LocateStatus.PENDING)
            .fixLocateReqId("LOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
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

            // Create a custom message for locate request
            // Note: This may need to be adapted based on your broker's FIX implementation
            // Some brokers use custom message types or specific fields
            // For FIX 4.2, we'll use ClOrdID as the locate request ID
            Message locateMsg = new Message();
            locateMsg.getHeader().setString(MsgType.FIELD, "L"); // LocateRequest (if supported)
            locateMsg.setString(ClOrdID.FIELD, locateRequest.getFixLocateReqId());
            locateMsg.setString(Symbol.FIELD, locateRequest.getSymbol());
            locateMsg.setDouble(OrderQty.FIELD, locateRequest.getQuantity().doubleValue());
            locateMsg.setString(quickfix.field.Account.FIELD, locateRequest.getAccount().getAccountNumber());
            // TransactTime is optional for locate requests, skip for now
            // locateMsg.setUtcTimeStamp(TransactTime.FIELD, new java.util.Date());

            session.send(locateMsg);
            log.info("Sent locate request: LocateReqID={}, Symbol={}, Qty={}, Retry={}", 
                locateRequest.getFixLocateReqId(), locateRequest.getSymbol(), locateRequest.getQuantity(), retryCount);
            
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
                                     BigDecimal availableQty,
                                     String locateId,
                                     String responseMessage,
                                     SessionID initiatorSessionID) {
        LocateRequest locateRequest = locateRequestRepository.findById(locateRequestId)
            .orElseThrow(() -> new IllegalArgumentException("LocateRequest not found: " + locateRequestId));
        
        // Check if already processed
        if (locateRequest.getStatus() != LocateRequest.LocateStatus.PENDING) {
            log.warn("Locate request {} already processed with status {}, ignoring response", 
                locateRequest.getFixLocateReqId(), locateRequest.getStatus());
            return;
        }
        
        log.info("Processing locate response: LocateReqID={}, Approved={}, AvailableQty={}, LocateID={}", 
            locateRequest.getFixLocateReqId(), approved, availableQty, locateId);

        if (!approved) {
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage(responseMessage != null ? responseMessage : "Locate request rejected");
            locateRequestRepository.save(locateRequest);
            log.warn("Locate request rejected: {}", responseMessage);
            notifyLocateFailure(locateRequest, responseMessage != null ? responseMessage : "Locate rejected");
            return;
        }

        if (availableQty == null || availableQty.compareTo(BigDecimal.ZERO) <= 0) {
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage("Invalid available quantity: " + availableQty);
            locateRequestRepository.save(locateRequest);
            log.warn("Locate request rejected: invalid available quantity");
            notifyLocateFailure(locateRequest, "Invalid available quantity");
            return;
        }

        if (availableQty.compareTo(locateRequest.getQuantity()) < 0) {
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage("Insufficient quantity available. Requested: " +
                locateRequest.getQuantity() + ", Available: " + availableQty);
            locateRequestRepository.save(locateRequest);
            log.warn("Locate request rejected: insufficient quantity (requested: {}, available: {})",
                locateRequest.getQuantity(), availableQty);
            notifyLocateFailure(locateRequest, "Insufficient locate quantity");
            return;
        }

        try {
            // Update locate request status
            locateRequest.setStatus(LocateRequest.LocateStatus.APPROVED);
            locateRequest.setAvailableQty(availableQty);
            locateRequest.setLocateId(locateId);
            if (responseMessage != null) {
                locateRequest.setResponseMessage(responseMessage);
            }
            locateRequestRepository.save(locateRequest);

            // Borrow the stock
            borrowStock(locateRequest);

            notifyLocateSuccess(locateRequest, availableQty, locateId, responseMessage);
        } catch (Exception e) {
            log.error("Error processing locate response for LocateReqID={}", locateRequest.getFixLocateReqId(), e);
            // Update status to indicate error
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage("Error processing locate response: " + e.getMessage());
            locateRequestRepository.save(locateRequest);
            shortLocateCoordinator.completeExceptionally(resolvePrimaryOrderId(locateRequest), e);
        }
    }
    
    /**
     * Process locate response by LocateReqID (FIX LocateReqID).
     */
    @Transactional
    public void processLocateResponseByLocateReqId(String fixLocateReqId,
                                                   boolean approved,
                                                   BigDecimal availableQty,
                                                   String locateId,
                                                   String responseMessage,
                                                   SessionID initiatorSessionID) {
        LocateRequest locateRequest = locateRequestRepository.findByFixLocateReqId(fixLocateReqId)
            .orElseThrow(() -> new IllegalArgumentException("LocateRequest not found: " + fixLocateReqId));
        
        processLocateResponse(locateRequest.getId(), approved, availableQty, locateId, responseMessage, initiatorSessionID);
    }

    /**
     * Borrow the stock (update locate request status to BORROWED).
     * In a real implementation, this might involve additional broker API calls.
     */
    @Transactional
    private void borrowStock(LocateRequest locateRequest) {
        log.info("Borrowing stock: Symbol={}, Qty={}, LocateID={}",
            locateRequest.getSymbol(), locateRequest.getQuantity(), locateRequest.getLocateId());

        locateRequest.setStatus(LocateRequest.LocateStatus.BORROWED);
        locateRequest.setBorrowedQty(locateRequest.getQuantity());
        locateRequestRepository.save(locateRequest);

        log.info("Stock borrowed successfully: Symbol={}, Qty={}",
            locateRequest.getSymbol(), locateRequest.getBorrowedQty());
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
     * Notify locate success to the coordinator.
     */
    private void notifyLocateSuccess(LocateRequest locateRequest, BigDecimal approvedQty, String locateId, String message) {
        String primaryOrderId = resolvePrimaryOrderId(locateRequest);
        if (primaryOrderId != null) {
            shortLocateCoordinator.completeSuccess(primaryOrderId, approvedQty, locateId, message);
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
