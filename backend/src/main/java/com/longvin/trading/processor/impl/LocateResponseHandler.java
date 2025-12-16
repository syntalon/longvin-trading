package com.longvin.trading.processor.impl;

import com.longvin.trading.service.ShortOrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.MsgType;
import quickfix.field.OfferPx;
import quickfix.field.OfferSize;
import quickfix.field.QuoteReqID;
import quickfix.field.Text;

import java.math.BigDecimal;

/**
 * Handles Short Locate Quote Response messages from DAS (quote-style locate protocol).
 *
 * Expected:
 * - MsgType=S (Short Locate Quote Response)
 * - 131 QuoteReqID (echoed back from request MsgType=R)
 * - 133 OfferPx (fee per share)
 * - 135 OfferSize (available shares; <=0 means rejected)
 * - 58 Text (optional)
 */
@Component
public class LocateResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(LocateResponseHandler.class);

    private final ShortOrderProcessingService shortOrderProcessingService;

    public LocateResponseHandler(ShortOrderProcessingService shortOrderProcessingService) {
        this.shortOrderProcessingService = shortOrderProcessingService;
    }

    public void processLocateResponse(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if (!"S".equals(msgType)) {
                return;
            }

            String quoteReqId = null;
            if (message.isSetField(QuoteReqID.FIELD)) {
                quoteReqId = message.getString(QuoteReqID.FIELD);
            } else if (message.isSetField(ClOrdID.FIELD)) {
                // fallback some counterparties use
                quoteReqId = message.getString(ClOrdID.FIELD);
            }

            if (quoteReqId == null || quoteReqId.isBlank()) {
                log.warn("Short Locate Quote Response missing QuoteReqID (131); cannot correlate. message={}", message);
                return;
            }

            BigDecimal offerPx = null;
            if (message.isSetField(OfferPx.FIELD)) {
                offerPx = BigDecimal.valueOf(message.getDouble(OfferPx.FIELD));
            }

            BigDecimal offerSize = BigDecimal.ZERO;
            if (message.isSetField(OfferSize.FIELD)) {
                offerSize = BigDecimal.valueOf(message.getDouble(OfferSize.FIELD));
            }

            String responseMessage = null;
            if (message.isSetField(Text.FIELD)) {
                responseMessage = message.getString(Text.FIELD);
            }

            log.info("Processing Short Locate Quote Response: QuoteReqID={}, OfferPx={}, OfferSize={}, Message={}",
                    quoteReqId, offerPx, offerSize, responseMessage);

            shortOrderProcessingService.processLocateResponseByQuoteReqId(
                    quoteReqId,
                    offerPx,
                    offerSize,
                    responseMessage,
                    sessionID
            );
        } catch (FieldNotFound e) {
            log.warn("Error processing Short Locate Quote Response: missing required field", e);
        } catch (Exception e) {
            log.error("Error processing Short Locate Quote Response", e);
        }
    }
}


