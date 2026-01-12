package com.longvin.trading.service;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.repository.LocateRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for processing short orders.
 * When a short order is detected for the primary account, this service:
 * Directly replicates the order to shadow accounts (stock should already be borrowed).
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
    private final AccountCacheService accountCacheService;
    private final FixMessageSender fixMessageSender;
    private final FixSessionRegistry fixSessionRegistry;

    public ShortOrderProcessingService(LocateRequestRepository locateRequestRepository,
                                       ShortLocateCoordinator shortLocateCoordinator,
                                       ShortOrderPlacementService placementService,
                                       AccountCacheService accountCacheService,
                                       FixMessageSender fixMessageSender,
                                       FixSessionRegistry fixSessionRegistry) {
        this.locateRequestRepository = locateRequestRepository;
        this.shortLocateCoordinator = shortLocateCoordinator;
        this.placementService = placementService;
        this.accountCacheService = accountCacheService;
        this.fixMessageSender = fixMessageSender;
        this.fixSessionRegistry = fixSessionRegistry;
    }

    /**
     * Check if an order is a short order (SELL_SHORT or SELL_SHORT_EXEMPT).
     */
    public boolean isShortOrder(char side) {
        return side == SIDE_SELL_SHORT || side == SIDE_SELL_SHORT_EXEMPT;
    }

    /**
     * Process a short order by directly replicating it to shadow accounts.
     * Stock should already be borrowed before this is called.
     * 
     * @param order The order entity (primary account order)
     * @param symbol Symbol to replicate
     * @param quantity Quantity to replicate
     * @param initiatorSessionID Session to send orders
     */
    @Transactional
    public void processShortOrder(Order order, String symbol, BigDecimal quantity, SessionID initiatorSessionID) {
        log.info("Processing short order replication for symbol={}, quantity={}, orderId={}", 
            symbol, quantity, order != null ? order.getId() : "unknown");

        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot replicate short order to shadow accounts");
            return;
        }
        SessionID sessionID = initiatorSessionOpt.get();

        // Get shadow accounts
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccounts();
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found, cannot replicate short order");
            return;
        }

        // Send orders to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                sendShortOrderToShadowAccount(order, symbol, quantity, shadowAccount, sessionID);
            } catch (Exception e) {
                log.error("Error replicating short order to shadow account {}: {}", 
                        shadowAccount.getAccountNumber(), e.getMessage(), e);
            }
        }
    }

    /**
     * Send short order to a shadow account.
     */
    private void sendShortOrderToShadowAccount(Order primaryOrder, String symbol, BigDecimal quantity,
                                              Account shadowAccount, SessionID initiatorSessionID) throws SessionNotFound {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = generateShadowClOrdId(primaryOrder, shadowAccountNumber);

        // Build order parameters
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", primaryOrder.getSide() != null ? primaryOrder.getSide() : SIDE_SELL_SHORT);
        orderParams.put("symbol", symbol);
        orderParams.put("orderQty", quantity.intValue());
        orderParams.put("account", shadowAccountNumber);

        // Set order type (default to MARKET if not available)
        char ordType = primaryOrder.getOrdType() != null ? primaryOrder.getOrdType() : '1'; // MARKET
        orderParams.put("ordType", ordType);

        // Set time in force (default to DAY if not available)
        char timeInForce = primaryOrder.getTimeInForce() != null ? primaryOrder.getTimeInForce() : '0'; // DAY
        orderParams.put("timeInForce", timeInForce);

        // Set price if it's a limit order
        if (primaryOrder.getPrice() != null && (ordType == '2' || ordType == '4')) { // LIMIT or STOP_LIMIT
            orderParams.put("price", primaryOrder.getPrice().doubleValue());
        }

        // Send order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);

        log.info("Replicated short order to shadow account {}: ClOrdID={}, Symbol={}, Side={}, Qty={}",
                shadowAccountNumber, clOrdId, symbol, primaryOrder.getSide(), quantity);
    }

    /**
     * Generate ClOrdID for shadow order.
     */
    private String generateShadowClOrdId(Order primaryOrder, String shadowAccountNumber) {
        String primaryClOrdId = primaryOrder.getFixClOrdId() != null 
            ? primaryOrder.getFixClOrdId() 
            : (primaryOrder.getFixOrderId() != null ? primaryOrder.getFixOrderId() : "UNKNOWN");
        
        // Simple format: prefix + shadow account + original ClOrdID
        return "COPY-" + shadowAccountNumber + "-" + primaryClOrdId;
    }

    /**
     * Send a FIX LocateRequest message.
     * Note: FIX 4.2 may not have standard LocateRequest. This is a placeholder
     * that can be adapted to your broker's specific locate request format.
     * 
     * @param locateRequest The locate request to send
     * @param initiatorSessionID The session to send on
     */
    @Transactional
    @SuppressWarnings("resource")
    private void sendLocateRequest(LocateRequest locateRequest, SessionID initiatorSessionID) {
        try {
            Session session = Session.lookupSession(initiatorSessionID);
            if (session == null || !session.isLoggedOn()) {
                log.error("Cannot send locate request: session {} is not logged on", initiatorSessionID);

                locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
                locateRequest.setResponseMessage("Session not logged on");
                locateRequestRepository.save(locateRequest);
                notifyLocateFailure(locateRequest, "Session not logged on");
                return;
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
                locateMsg.setString(ClOrdID.FIELD, "LOC-" + System.currentTimeMillis());
            }
            
            locateMsg.setString(Symbol.FIELD, locateRequest.getSymbol());
            locateMsg.setDouble(OrderQty.FIELD, locateRequest.getQuantity().doubleValue());
            locateMsg.setString(quickfix.field.Account.FIELD, locateRequest.getAccount().getAccountNumber());
            
            // Tag 100: ExDestination (locate route) - if configured
            if (locateRequest.getLocateRoute() != null && !locateRequest.getLocateRoute().isEmpty()) {
                locateMsg.setString(ExDestination.FIELD, locateRequest.getLocateRoute());
            }

            session.send(locateMsg);
            log.info("Sent Short Locate Quote Request (MsgType=R): QuoteReqID={}, Symbol={}, Qty={}, Route={},ShortLocateQuoteRequest={}",
                locateRequest.getFixQuoteReqId(), locateRequest.getSymbol(), locateRequest.getQuantity(),
                locateRequest.getLocateRoute(),locateMsg);

        } catch (Exception e) {
            log.error("Error sending locate request for order {}: {}",
                locateRequest.getOrder().getId(), e.getMessage(), e);

            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage("Error sending locate request: " + e.getMessage());
            locateRequestRepository.save(locateRequest);
            notifyLocateFailure(locateRequest, "Error sending locate request: " + e.getMessage());
        }
    }

    /**
     * Process a locate response (when broker responds to locate request).
     * If approved, borrow the stock and place orders for shadow accounts.
     * 
     * @param locateRequestId The locate request ID that was responded to
     * @param approved Whether locate was approved
     * @param offerPx Offer price per share
     * @param offerSize Quantity available for borrowing
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

        if (offerSize == null || offerSize.compareTo(BigDecimal.ZERO) <= 0) {
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

            // OfferSize is sufficient for at least some qty; per workflow we must send Locate Accept (p)
            // and wait for a later ExecutionReport (OrdStatus=B) before placing shadow short orders.
            sendLocateAccept(locateRequest, approvedQty, initiatorSessionID);
        } catch (Exception e) {
            log.error("Error processing locate response for QuoteReqID={}", locateRequest.getFixQuoteReqId(), e);
            // Update status to indicate error
            locateRequest.setStatus(LocateRequest.LocateStatus.REJECTED);
            locateRequest.setResponseMessage("Error processing locate response: " + e.getMessage());
            locateRequestRepository.save(locateRequest);
            shortLocateCoordinator.completeExceptionally(resolvePrimaryOrderId(locateRequest), e);
        }
    }

    @Transactional
    public void processLocateConfirmationByQuoteReqId(String fixQuoteReqId,
                                                     SessionID initiatorSessionID,
                                                     String confirmationMessage) {
        LocateRequest locateRequest = locateRequestRepository.findByFixQuoteReqId(fixQuoteReqId)
                .orElseThrow(() -> new IllegalArgumentException("LocateRequest not found: " + fixQuoteReqId));

        BigDecimal approvedQty = locateRequest.getApprovedQty();
        if (approvedQty == null || approvedQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Locate confirmation received for QuoteReqID={} but approvedQty is missing/zero", fixQuoteReqId);
            notifyLocateFailure(locateRequest, "Locate confirmed but approved quantity is missing");
            return;
        }

        log.info("Locate confirmed (OrdStatus=B) for QuoteReqID={}, approvedQty={}", fixQuoteReqId, approvedQty);
        notifyLocateSuccess(locateRequest, approvedQty, locateRequest.getOfferPx(), confirmationMessage, initiatorSessionID);
    }

    @SuppressWarnings("resource")
    private void sendLocateAccept(LocateRequest locateRequest, BigDecimal approvedQty, SessionID initiatorSessionID) {
        try {
            Session session = Session.lookupSession(initiatorSessionID);
            if (session == null || !session.isLoggedOn()) {
                log.error("Cannot send locate accept: session {} is not logged on", initiatorSessionID);
                notifyLocateFailure(locateRequest, "Cannot send locate accept: initiator session not logged on");
                return;
            }

            // Send Short Locate New Order (MsgType=D) to "buy" the locate
            // Per DAS Spec: "Short Locate New Order/Execution"
            // Tag 100 Locate Route (must be set)
            // Tag 54 Side = 1 (Always BUY)
            // Tag 40 OrdType (Ignored)
            // Tag 59 TimeInForce (Ignored)

            NewOrderSingle locateOrder = new NewOrderSingle();

            // Use QuoteReqID as ClOrdID so we can match the execution report later
            if (locateRequest.getFixQuoteReqId() != null) {
                locateOrder.set(new ClOrdID(locateRequest.getFixQuoteReqId()));
            } else {
                locateOrder.set(new ClOrdID("LOC-EXEC-" + System.currentTimeMillis()));
            }

            locateOrder.set(new Symbol(locateRequest.getSymbol()));
            locateOrder.set(new Side(Side.BUY)); // Always BUY for locate execution
            locateOrder.set(new OrderQty(approvedQty.doubleValue()));
            locateOrder.set(new quickfix.field.Account(locateRequest.getAccount().getAccountNumber()));

            // Required but ignored by DAS for locate
            locateOrder.set(new OrdType(OrdType.MARKET));
            locateOrder.set(new TimeInForce(TimeInForce.DAY));
            locateOrder.set(new TransactTime(java.time.LocalDateTime.now()));

            // Important: Set Locate Route
            if (locateRequest.getLocateRoute() != null) {
                locateOrder.set(new ExDestination(locateRequest.getLocateRoute()));
            } else {
                log.warn("Locate route missing for request {}, defaulting to 'LOCATE'", locateRequest.getFixQuoteReqId());
                locateOrder.set(new ExDestination("LOCATE"));
            }

            session.send(locateOrder);
            log.info("Sent Short Locate New Order (MsgType=D): ClOrdID={}, Symbol={}, Qty={}, Route={}",
                    locateRequest.getFixQuoteReqId(), locateRequest.getSymbol(), approvedQty, locateRequest.getLocateRoute());
        } catch (Exception e) {
            log.error("Error sending locate execution order for QuoteReqID={}: {}", locateRequest.getFixQuoteReqId(), e.getMessage(), e);
            notifyLocateFailure(locateRequest, "Error sending locate execution order: " + e.getMessage());
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
            // Pass offerPx if needed by placementService, otherwise remove it from method signature
            // For now, we'll just log it to suppress the warning
            log.debug("Locate success for offerPx={}", offerPx);

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
