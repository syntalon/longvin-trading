package com.longvin.simulator.service;

import com.longvin.simulator.config.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;

import java.util.Locale;

/**
 * Service for generating and sending LocateResponse messages.
 */
@Slf4j
@Service
public class LocateResponseService {

    private final SimulatorProperties properties;

    public LocateResponseService(SimulatorProperties properties) {
        this.properties = properties;
    }

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

    /**
     * Send a Short Locate Quote Response (MsgType=S) in response to a Short Locate Quote Request (MsgType=R).
     * Expected tags per DAS spec:
     * - 131 QuoteReqID (echoed back)
     * - 55 Symbol
     * - 38 OrderQty (requested locate size)
     * Response:
     * - 133 OfferPx
     * - 135 OfferSize (<=0 means fail)
     * - 58 Text (optional)
     */
    public void sendShortLocateQuoteResponse(SessionID sessionID, Message quoteRequest) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send Short Locate Quote Response: session {} is not logged on", sessionID);
                return;
            }

            SimulatorProperties.ShortLocateConfig cfg = properties.getShortLocate();
            if (cfg == null || !cfg.isEnabled()) {
                log.warn("Short locate simulation disabled (simulator.short-locate.enabled=false). Ignoring quote request.");
                return;
            }

            String quoteReqId = quoteRequest.isSetField(QuoteReqID.FIELD)
                    ? quoteRequest.getString(QuoteReqID.FIELD)
                    : (quoteRequest.isSetField(ClOrdID.FIELD) ? quoteRequest.getString(ClOrdID.FIELD) : null);
            if (quoteReqId == null || quoteReqId.isBlank()) {
                quoteReqId = "QUOTE-" + System.currentTimeMillis();
            }

            String symbol = quoteRequest.isSetField(Symbol.FIELD) ? quoteRequest.getString(Symbol.FIELD) : "UNKNOWN";
            double requestedQty = quoteRequest.isSetField(OrderQty.FIELD) ? quoteRequest.getDouble(OrderQty.FIELD) : 0.0d;

            double offerPx = cfg.getOfferPx();
            double offerSize = computeOfferSize(cfg, requestedQty);

            Message quoteResponse = new Message();
            quoteResponse.getHeader().setString(MsgType.FIELD, "S"); // Short Locate Quote Response
            quoteResponse.setString(QuoteReqID.FIELD, quoteReqId);
            quoteResponse.setString(Symbol.FIELD, symbol);
            quoteResponse.setDouble(OfferPx.FIELD, offerPx);
            quoteResponse.setDouble(OfferSize.FIELD, offerSize);
            quoteResponse.setString(Text.FIELD, offerSize > 0
                    ? ("Locate quote OK: OfferSize=" + offerSize + " OfferPx=" + offerPx)
                    : "Locate quote rejected (OfferSize <= 0)");

            session.send(quoteResponse);
            log.info("Simulator sent Short Locate Quote Response (MsgType=S): QuoteReqID={}, Symbol={}, RequestedQty={}, OfferPx={}, OfferSize={}, Mode={}",
                    quoteReqId, symbol, requestedQty, offerPx, offerSize, cfg.getOfferMode());
        } catch (Exception e) {
            log.error("Error sending Short Locate Quote Response", e);
        }
    }

    /**
     * After receiving Locate Accept (MsgType=p), send an ExecutionReport with OrdStatus=B to confirm the locate.
     * Backend listens for OrdStatus=B and correlates by QuoteReqID (tag 131) or ClOrdID.
     */
    public void sendLocateAcceptConfirmation(SessionID sessionID, Message locateAccept) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send locate confirmation ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            SimulatorProperties.ShortLocateConfig cfg = properties.getShortLocate();
            if (cfg == null || !cfg.isEnabled()) {
                log.warn("Short locate simulation disabled (simulator.short-locate.enabled=false). Ignoring locate accept.");
                return;
            }

            String quoteReqId = locateAccept.isSetField(QuoteReqID.FIELD)
                    ? locateAccept.getString(QuoteReqID.FIELD)
                    : (locateAccept.isSetField(ClOrdID.FIELD) ? locateAccept.getString(ClOrdID.FIELD) : null);
            if (quoteReqId == null || quoteReqId.isBlank()) {
                quoteReqId = "QUOTE-" + System.currentTimeMillis();
            }

            String symbol = locateAccept.isSetField(Symbol.FIELD) ? locateAccept.getString(Symbol.FIELD) : "UNKNOWN";
            double acceptedQty = locateAccept.isSetField(OrderQty.FIELD) ? locateAccept.getDouble(OrderQty.FIELD) : 0.0d;
            String account = locateAccept.isSetField(Account.FIELD) ? locateAccept.getString(Account.FIELD) : null;

            ExecutionReport report = new ExecutionReport(
                    new OrderID("LOC-" + System.currentTimeMillis()),
                    new ExecID("LOCEXEC-" + System.currentTimeMillis()),
                    new ExecTransType(ExecTransType.NEW),
                    new ExecType(ExecType.CALCULATED),
                    new OrdStatus('B'), // CALCULATED (DAS locate confirmation expected by backend)
                    new Symbol(symbol),
                    new Side(Side.SELL_SHORT),
                    new LeavesQty(0.0d),
                    new CumQty(0.0d),
                    new AvgPx(0.0d)
            );

            report.setString(QuoteReqID.FIELD, quoteReqId);
            report.setString(ClOrdID.FIELD, quoteReqId); // helpful fallback
            report.setDouble(OrderQty.FIELD, acceptedQty);
            if (account != null) {
                report.setString(Account.FIELD, account);
            }
            report.setString(Text.FIELD, "Locate confirmed for QuoteReqID=" + quoteReqId + ", Qty=" + acceptedQty);
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent locate confirmation ExecutionReport: QuoteReqID={}, OrdStatus=B, Symbol={}, Qty={}", quoteReqId, symbol, acceptedQty);
        } catch (Exception e) {
            log.error("Error sending locate confirmation ExecutionReport", e);
        }
    }

    private static double computeOfferSize(SimulatorProperties.ShortLocateConfig cfg, double requestedQty) {
        String mode = cfg.getOfferMode() == null ? "FULL" : cfg.getOfferMode().trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "ZERO" -> 0.0d;
            case "FIXED" -> cfg.getFixedOfferSize();
            case "RATIO" -> Math.max(0.0d, requestedQty * cfg.getRatio());
            case "FULL" -> requestedQty;
            default -> requestedQty;
        };
    }
}

