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
     * Process a Short Locate Quote Response message (MsgType=S).
     * Per DAS spec:
     * - Tag 131 = QuoteReqID (echoed back from request)
     * - Tag 133 = OfferPx (fee per share)
     * - Tag 135 = OfferSize (available shares; <=0 means fail)
     * - Tag 58 = Text (optional message)
     * 
     * @param message The Short Locate Quote Response message (MsgType=S)
     * @param sessionID The FIX session ID
     */
    public void processLocateResponse(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            
            // Check if this is a Short Locate Quote Response
            if (!"S".equals(msgType)) {
                log.trace("Not a Short Locate Quote Response (MsgType={}), ignoring", msgType);
                return;
            }

            // Extract QuoteReqID (tag 131) - this is echoed back from our request
            String quoteReqId = null;
            if (message.isSetField(quickfix.field.QuoteReqID.FIELD)) {
                quoteReqId = message.getString(quickfix.field.QuoteReqID.FIELD);
            } else if (message.isSetField(ClOrdID.FIELD)) {
                // Fallback: some brokers might use ClOrdID
                quoteReqId = message.getString(ClOrdID.FIELD);
            }

            if (quoteReqId == null || quoteReqId.isBlank()) {
                log.warn("Short Locate Quote Response missing QuoteReqID (tag 131), cannot match to request");
                return;
            }

            // Extract OfferPx (tag 133) - fee per share
            BigDecimal offerPx = null;
            if (message.isSetField(quickfix.field.OfferPx.FIELD)) {
                offerPx = BigDecimal.valueOf(message.getDouble(quickfix.field.OfferPx.FIELD));
            }

            // Extract OfferSize (tag 135) - available shares; <=0 means fail
            BigDecimal offerSize = BigDecimal.ZERO;
            if (message.isSetField(quickfix.field.OfferSize.FIELD)) {
                offerSize = BigDecimal.valueOf(message.getDouble(quickfix.field.OfferSize.FIELD));
            }

            // Extract Text (tag 58) - optional message
            String responseMessage = null;
            if (message.isSetField(Text.FIELD)) {
                responseMessage = message.getString(Text.FIELD);
            }

            log.info("Processing Short Locate Quote Response: QuoteReqID={}, OfferPx={}, OfferSize={}, Message={}", 
                quoteReqId, offerPx, offerSize, responseMessage);

            // Process the locate response
            try {
                shortOrderProcessingService.processLocateResponseByQuoteReqId(
                    quoteReqId,
                    offerPx,
                    offerSize,
                    responseMessage,
                    sessionID
                );
            } catch (IllegalArgumentException e) {
                log.warn("Cannot process locate response: QuoteReqID={} not found. This may be a response for a different system or an old request.", 
                    quoteReqId);
            } catch (Exception e) {
                log.error("Error processing locate response for QuoteReqID={}", quoteReqId, e);
            }

        } catch (FieldNotFound e) {
            log.warn("Error processing Short Locate Quote Response: missing required field", e);
        } catch (Exception e) {
            log.error("Error processing Short Locate Quote Response", e);
        }
    }
}

