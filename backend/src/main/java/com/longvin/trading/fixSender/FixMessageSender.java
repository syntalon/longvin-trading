package com.longvin.trading.fixSender;

import com.longvin.trading.executionReportHandler.FillOrderHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;

import java.util.Date;
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

        newOrder.setString(ClOrdID.FIELD, (String) params.get("clOrdID"));
        newOrder.setChar(Side.FIELD, (Character) params.get("side"));
        newOrder.setString(Symbol.FIELD, (String) params.get("symbol"));
        newOrder.setInt(OrderQty.FIELD, (Integer) params.get("orderQty"));
        newOrder.setChar(OrdType.FIELD, (Character) params.get("ordType"));
        newOrder.setChar(TimeInForce.FIELD, (Character) params.get("timeInForce"));

        if (params.containsKey("price")) {
            newOrder.setDouble(Price.FIELD, (Double) params.get("price"));
        }

        if (params.containsKey("account")) {
            newOrder.setString(Account.FIELD, (String) params.get("account"));
        }

        if (params.containsKey("exDestination")) {
            newOrder.setString(ExDestination.FIELD, (String) params.get("exDestination"));
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