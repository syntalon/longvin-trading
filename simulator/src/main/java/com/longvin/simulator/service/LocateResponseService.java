package com.longvin.simulator.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;

/**
 * Service for generating and sending LocateResponse messages.
 */
@Slf4j
@Service
public class LocateResponseService {

    /**
     * Send a LocateResponse in response to a LocateRequest.
     * By default, approves the locate request with the full requested quantity.
     */
    public void sendLocateResponse(SessionID sessionID, Message locateRequest) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send LocateResponse: session {} is not logged on", sessionID);
                return;
            }

            // Extract fields from locate request
            String locateReqId = locateRequest.isSetField(ClOrdID.FIELD) 
                ? locateRequest.getString(ClOrdID.FIELD) 
                : "LOC-" + System.currentTimeMillis();
            String symbol = locateRequest.isSetField(Symbol.FIELD) 
                ? locateRequest.getString(Symbol.FIELD) 
                : "UNKNOWN";
            double requestedQty = locateRequest.isSetField(OrderQty.FIELD) 
                ? locateRequest.getDouble(OrderQty.FIELD) 
                : 0.0;

            // Create locate response message (MsgType="M")
            Message locateResponse = new Message();
            locateResponse.getHeader().setString(MsgType.FIELD, "M"); // LocateResponse
            
            // Use ClOrdID to match the locate request
            locateResponse.setString(ClOrdID.FIELD, locateReqId);
            
            // Set approval status (OrdStatus: '0' = NEW/Approved, '8' = REJECTED)
            // For testing, we'll approve by default with full quantity
            locateResponse.setChar(OrdStatus.FIELD, '0'); // Approved
            
            // Set available quantity (approve full requested quantity)
            locateResponse.setDouble(OrderQty.FIELD, requestedQty);
            locateResponse.setDouble(LeavesQty.FIELD, requestedQty); // Available quantity
            
            // Set locate ID
            String locateId = "LOCATE-" + System.currentTimeMillis();
            locateResponse.setString(OrderID.FIELD, locateId);
            
            // Set symbol
            locateResponse.setString(Symbol.FIELD, symbol);
            
            // Optional: Set response message
            locateResponse.setString(Text.FIELD, "Locate approved for " + requestedQty + " shares of " + symbol);

            session.send(locateResponse);
            log.info("Simulator sent LocateResponse (session: {}): LocateReqID={}, Symbol={}, ApprovedQty={}, LocateID={}", 
                sessionID, locateReqId, symbol, requestedQty, locateId);
        } catch (Exception e) {
            log.error("Error sending LocateResponse: {}", e.getMessage(), e);
        }
    }
}

