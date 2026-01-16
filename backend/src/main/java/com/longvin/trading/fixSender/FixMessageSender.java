package com.longvin.trading.fixSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;

import java.util.Map;

@Component
public class FixMessageSender {
    private static final Logger log = LoggerFactory.getLogger(FixMessageSender.class);

    private void logSendFailure(String action, SessionID sessionID, String key, String value, Exception e) {
        log.error("Failed to send {} (sessionID={}, {}={})", action, sessionID, key, value, e);
    }

    public void sendShortLocateQuoteRequest(SessionID sessionID, String symbol, int qty,
                                            String account, String locateRoute, String quoteReqID) {

        Message quoteReq = new Message();
        quickfix.Message.Header header = quoteReq.getHeader();

        header.setString(MsgType.FIELD, "R"); // Short Locate Quote Request
        //header.setString(SenderCompID.FIELD, sessionID.getSenderCompID());
        //header.setString(TargetCompID.FIELD, sessionID.getTargetCompID());
        //header.setUtcTimeStamp(SendingTime.FIELD, new Date());

        quoteReq.setString(Symbol.FIELD, symbol);
        quoteReq.setInt(OrderQty.FIELD, qty);
        quoteReq.setString(Account.FIELD, account);
        quoteReq.setString(ExDestination.FIELD, locateRoute);
        quoteReq.setString(QuoteReqID.FIELD, quoteReqID);

        try {
            Session.sendToTarget(quoteReq, sessionID);
            log.info("Short Locate Quote Request Message : {} sent to {}", quoteReq, sessionID);
        } catch (SessionNotFound e) {
            logSendFailure("Short Locate Quote Request", sessionID, "quoteReqID", quoteReqID, e);
        }
    }

    public void sendShortLocateAcceptOffer(SessionID sessionID, String orderID) {
        Message acceptMsg = new Message();
        quickfix.Message.Header header = acceptMsg.getHeader();

        header.setString(MsgType.FIELD, "p"); // Short Locate Accept/Reject offer
        header.setString(SenderCompID.FIELD, sessionID.getSenderCompID());
        header.setString(TargetCompID.FIELD, sessionID.getTargetCompID());
        //header.setUtcTimeStamp(SendingTime.FIELD, new Date());

        String quoteID = orderID + ",1"; // 1=accept, 0=reject
        acceptMsg.setString(QuoteID.FIELD, quoteID);

        try {
            Session.sendToTarget(acceptMsg, sessionID);
            log.info("Short Locate Accept Message : {} sent to {}", acceptMsg, sessionID);
        } catch (SessionNotFound e) {
            logSendFailure("Short Locate Accept", sessionID, "orderID", orderID, e);
        }
    }
    
    public void sendShortLocateRejectOffer(SessionID sessionID, String orderID) {
        Message rejectMsg = new Message();
        quickfix.Message.Header header = rejectMsg.getHeader();

        header.setString(MsgType.FIELD, "p"); // Short Locate Accept/Reject offer
        header.setString(SenderCompID.FIELD, sessionID.getSenderCompID());
        header.setString(TargetCompID.FIELD, sessionID.getTargetCompID());
        //header.setUtcTimeStamp(SendingTime.FIELD, new Date());

        String quoteID = orderID + ",0"; // 1=accept, 0=reject
        rejectMsg.setString(QuoteID.FIELD, quoteID);

        try {
            Session.sendToTarget(rejectMsg, sessionID);
            log.info("Short Locate Reject Message : {} sent to {}", rejectMsg, sessionID);
        } catch (SessionNotFound e) {
            logSendFailure("Short Locate Reject", sessionID, "orderID", orderID, e);
        }
    }

    public void sendNewOrderSingle(SessionID sessionID, Map<String, Object> params) {
        Message newOrder = new Message();
        quickfix.Message.Header header = newOrder.getHeader();

        header.setString(MsgType.FIELD, MsgType.ORDER_SINGLE);
        header.setString(SenderCompID.FIELD, sessionID.getSenderCompID());
        header.setString(TargetCompID.FIELD, sessionID.getTargetCompID());
        //header.setUtcTimeStamp(SendingTime.FIELD, new Date());

        // Validate and set required fields (null values are not allowed by QuickFIX/J)
        String clOrdID = (String) params.get("clOrdID");
        if (clOrdID == null || clOrdID.isBlank()) {
            throw new IllegalArgumentException("clOrdID is required and cannot be null or blank");
        }
        newOrder.setString(ClOrdID.FIELD, clOrdID);

        Character side = (Character) params.get("side");
        if (side == null || side == 0) {
            throw new IllegalArgumentException("side is required and cannot be null or zero");
        }
        newOrder.setChar(Side.FIELD, side);

        String symbol = (String) params.get("symbol");
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required and cannot be null or blank");
        }
        newOrder.setString(Symbol.FIELD, symbol);

        Integer orderQty = (Integer) params.get("orderQty");
        if (orderQty == null || orderQty <= 0) {
            throw new IllegalArgumentException("orderQty is required and must be greater than 0");
        }
        newOrder.setInt(OrderQty.FIELD, orderQty);

        Character ordType = (Character) params.get("ordType");
        if (ordType == null) {
            throw new IllegalArgumentException("ordType is required and cannot be null");
        }
        newOrder.setChar(OrdType.FIELD, ordType);

        Character timeInForce = (Character) params.get("timeInForce");
        if (timeInForce == null) {
            throw new IllegalArgumentException("timeInForce is required and cannot be null");
        }
        newOrder.setChar(TimeInForce.FIELD, timeInForce);

        if (params.containsKey("price")) {
            Double price = (Double) params.get("price");
            if (price != null && price > 0) {
                newOrder.setDouble(Price.FIELD, price);
            }
        }

        if (params.containsKey("stopPx")) {
            Double stopPx = (Double) params.get("stopPx");
            if (stopPx != null && stopPx > 0) {
                newOrder.setDouble(StopPx.FIELD, stopPx);
            }
        }

        if (params.containsKey("account")) {
            String account = (String) params.get("account");
            if (account != null && !account.isBlank()) {
                newOrder.setString(Account.FIELD, account);
                log.debug("Setting Account field in FIX message: Account={}, ClOrdID={}", account, clOrdID);
            } else {
                log.warn("Account parameter is null or blank for ClOrdID={}, Account field will not be set", clOrdID);
            }
        } else {
            log.warn("Account parameter missing in orderParams for ClOrdID={}, Account field will not be set", clOrdID);
        }

        if (params.containsKey("exDestination")) {
            String exDestination = (String) params.get("exDestination");
            if (exDestination != null && !exDestination.isBlank()) {
                newOrder.setString(ExDestination.FIELD, exDestination);
            }
        }

        try {
            Session.sendToTarget(newOrder, sessionID);
            log.info("New Order Single Message : {} sent to {}", newOrder, sessionID);
        } catch (SessionNotFound e) {
            Object clOrdId = params.get("clOrdID");
            logSendFailure("New Order Single", sessionID, "clOrdID", clOrdId != null ? clOrdId.toString() : "null", e);
        }
    }

}