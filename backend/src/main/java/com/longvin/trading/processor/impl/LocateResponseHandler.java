package com.longvin.trading.processor.impl;

import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;

import java.math.BigDecimal;

/**
 * Handler for locate response messages from the broker.
 * When a locate request is approved/rejected, this handler processes the response
 * and triggers stock borrowing and shadow order placement.
 */
@Component
public class LocateResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(LocateResponseHandler.class);

    private final ShortOrderProcessingService shortOrderProcessingService;

    public LocateResponseHandler(ShortOrderProcessingService shortOrderProcessingService) {
        this.shortOrderProcessingService = shortOrderProcessingService;
    }

    /**
     * Process a locate response message.
     * Expected message types:
     * - "M" = LocateResponse (if standard FIX)
     * - Custom message types may vary by broker
     * 
     * @param message The locate response message
     * @param sessionID The FIX session ID
     */
    public void processLocateResponse(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            
            // Check if this is a locate response
            // Note: FIX 4.2 may use "M" for LocateResponse, or custom message types
            if (!"M".equals(msgType)) {
                log.trace("Not a locate response message (MsgType={}), ignoring", msgType);
                return;
            }

            // Extract LocateReqID to match with our request
            // Note: FIX 4.2 may not have LocateReqID field, so we use ClOrdID
            String locateReqId = null;
            if (message.isSetField(ClOrdID.FIELD)) {
                // Use ClOrdID as LocateReqID (some brokers use this)
                locateReqId = message.getString(ClOrdID.FIELD);
            }

            if (locateReqId == null || locateReqId.isBlank()) {
                log.warn("Locate response missing LocateReqID/ClOrdID, cannot match to request");
                return;
            }

            // Extract response details
            boolean approved = false;
            BigDecimal availableQty = BigDecimal.ZERO;
            String locateId = null;
            String responseMessage = null;

            // Check for LocateStatus or similar field
            // This may vary by broker - adjust based on your broker's FIX implementation
            if (message.isSetField(OrdStatus.FIELD)) {
                char status = message.getChar(OrdStatus.FIELD);
                approved = (status == '0' || status == '1'); // NEW or PARTIALLY_FILLED might indicate approved
            }

            // Extract available quantity
            if (message.isSetField(OrderQty.FIELD)) {
                availableQty = BigDecimal.valueOf(message.getDouble(OrderQty.FIELD));
            } else if (message.isSetField(LeavesQty.FIELD)) {
                availableQty = BigDecimal.valueOf(message.getDouble(LeavesQty.FIELD));
            }

            // Extract locate ID (may be in OrderID or custom field)
            if (message.isSetField(OrderID.FIELD)) {
                locateId = message.getString(OrderID.FIELD);
            }

            // Extract response message
            if (message.isSetField(Text.FIELD)) {
                responseMessage = message.getString(Text.FIELD);
            }

            // If no explicit approval status, check if quantity > 0
            if (!approved && availableQty.compareTo(BigDecimal.ZERO) > 0) {
                approved = true;
            }

            log.info("Processing locate response: LocateReqID={}, Approved={}, AvailableQty={}, LocateID={}, Message={}", 
                locateReqId, approved, availableQty, locateId, responseMessage);

            // Process the locate response
            try {
                shortOrderProcessingService.processLocateResponseByLocateReqId(
                    locateReqId,
                    approved,
                    availableQty,
                    locateId,
                    responseMessage,
                    sessionID
                );
            } catch (IllegalArgumentException e) {
                log.warn("Cannot process locate response: LocateReqID={} not found. This may be a response for a different system or an old request.", 
                    locateReqId);
            } catch (Exception e) {
                log.error("Error processing locate response for LocateReqID={}", locateReqId, e);
            }

        } catch (FieldNotFound e) {
            log.warn("Error processing locate response: missing required field", e);
        } catch (Exception e) {
            log.error("Error processing locate response", e);
        }
    }
}

