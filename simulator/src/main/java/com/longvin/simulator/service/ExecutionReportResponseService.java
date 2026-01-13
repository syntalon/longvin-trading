package com.longvin.simulator.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.NewOrderSingle;
import quickfix.fix42.OrderCancelRequest;
import quickfix.fix42.OrderCancelReplaceRequest;

/**
 * Service for generating and sending ExecutionReport responses to order requests.
 */
@Slf4j
@Service
public class ExecutionReportResponseService {

    /**
     * Send an ExecutionReport in response to a NewOrderSingle.
     */
    public void sendNewOrderExecutionReport(SessionID sessionID, NewOrderSingle order) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            char side = order.getSide().getValue();
            double orderQty = order.getOrderQty().getValue();
            double price = order.getPrice().getValue();
            Symbol symbol = order.getSymbol();
            
            ExecutionReport report = new ExecutionReport(
                new OrderID("SIM-" + System.currentTimeMillis()),
                new ExecID("EXEC-" + System.currentTimeMillis()),
                new ExecTransType(ExecTransType.NEW),
                new ExecType(ExecType.FILL),
                new OrdStatus(OrdStatus.FILLED),
                symbol,
                new Side(side),
                new LeavesQty(0.0),
                new CumQty(orderQty),
                new AvgPx(price)
            );

            // Copy fields from original order
            if (order.isSetField(ClOrdID.FIELD)) {
                report.setString(ClOrdID.FIELD, order.getString(ClOrdID.FIELD));
            }
            if (order.isSetField(Symbol.FIELD)) {
                report.setString(Symbol.FIELD, order.getString(Symbol.FIELD));
            }
            if (order.isSetField(OrderQty.FIELD)) {
                report.set(new OrderQty(orderQty));
            }
            if (order.isSetField(Price.FIELD)) {
                report.set(new LastPx(price));
            }
            if (order.isSetField(Account.FIELD)) {
                report.setString(Account.FIELD, order.getString(Account.FIELD));
            }

            report.set(new LastShares(orderQty));
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent ExecutionReport (session: {}): OrderID={}, ExecType=FILL, OrdStatus=FILLED", 
                sessionID, report.getString(OrderID.FIELD));
        } catch (Exception e) {
            log.error("Error sending ExecutionReport: {}", e.getMessage(), e);
        }
    }

    /**
     * Send an ExecutionReport in response to an OrderCancelRequest.
     */
    public void sendCancelExecutionReport(SessionID sessionID, OrderCancelRequest cancelRequest) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send Cancel ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            // Extract fields from cancel request
            String clOrdId = cancelRequest.isSetField(ClOrdID.FIELD) 
                ? cancelRequest.getString(ClOrdID.FIELD) 
                : "CANCEL-" + System.currentTimeMillis();
            String origClOrdId = cancelRequest.isSetField(OrigClOrdID.FIELD) 
                ? cancelRequest.getString(OrigClOrdID.FIELD) 
                : clOrdId;
            String symbol = cancelRequest.isSetField(Symbol.FIELD) 
                ? cancelRequest.getString(Symbol.FIELD) 
                : "UNKNOWN";
            char side = cancelRequest.isSetField(Side.FIELD) 
                ? cancelRequest.getSide().getValue() 
                : Side.BUY;

            // Create ExecutionReport for canceled order
            ExecutionReport report = new ExecutionReport(
                new OrderID("ORD-" + System.currentTimeMillis()),
                new ExecID("EXEC-CANCEL-" + System.currentTimeMillis()),
                new ExecTransType(ExecTransType.NEW),
                new ExecType(ExecType.CANCELED),
                new OrdStatus(OrdStatus.CANCELED),
                new Symbol(symbol),
                new Side(side),
                new LeavesQty(0.0),
                new CumQty(0.0),
                new AvgPx(0.0)
            );

            // Set ClOrdID and OrigClOrdID
            report.setString(ClOrdID.FIELD, clOrdId);
            report.setString(OrigClOrdID.FIELD, origClOrdId);
            report.setString(Symbol.FIELD, symbol);
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent Cancel ExecutionReport (session: {}): ClOrdID={}, OrigClOrdID={}, ExecType=CANCELED", 
                sessionID, clOrdId, origClOrdId);
        } catch (Exception e) {
            log.error("Error sending Cancel ExecutionReport: {}", e.getMessage(), e);
        }
    }

    /**
     * Send an ExecutionReport in response to an OrderCancelReplaceRequest.
     */
    public void sendReplaceExecutionReport(SessionID sessionID, OrderCancelReplaceRequest replaceRequest) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send Replace ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            // Extract fields from replace request
            String clOrdId = replaceRequest.isSetField(ClOrdID.FIELD) 
                ? replaceRequest.getString(ClOrdID.FIELD) 
                : "REPLACE-" + System.currentTimeMillis();
            String origClOrdId = replaceRequest.isSetField(OrigClOrdID.FIELD) 
                ? replaceRequest.getString(OrigClOrdID.FIELD) 
                : clOrdId;
            String symbol = replaceRequest.isSetField(Symbol.FIELD) 
                ? replaceRequest.getString(Symbol.FIELD) 
                : "UNKNOWN";
            char side = replaceRequest.isSetField(Side.FIELD) 
                ? replaceRequest.getSide().getValue() 
                : Side.BUY;
            double orderQty = replaceRequest.isSetField(OrderQty.FIELD) 
                ? replaceRequest.getOrderQty().getValue() 
                : 0.0;
            double price = replaceRequest.isSetField(Price.FIELD) 
                ? replaceRequest.getPrice().getValue() 
                : 0.0;

            // Create ExecutionReport for replaced order
            ExecutionReport report = new ExecutionReport(
                new OrderID("ORD-" + System.currentTimeMillis()),
                new ExecID("EXEC-REPLACE-" + System.currentTimeMillis()),
                new ExecTransType(ExecTransType.NEW),
                new ExecType(ExecType.REPLACED),
                new OrdStatus(OrdStatus.REPLACED),
                new Symbol(symbol),
                new Side(side),
                new LeavesQty(orderQty),
                new CumQty(0.0),
                new AvgPx(0.0)
            );

            // Set ClOrdID and OrigClOrdID
            report.setString(ClOrdID.FIELD, clOrdId);
            report.setString(OrigClOrdID.FIELD, origClOrdId);
            report.setString(Symbol.FIELD, symbol);
            if (orderQty > 0) {
                report.set(new OrderQty(orderQty));
            }
            if (price > 0) {
                report.set(new LastPx(price));
            }
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent Replace ExecutionReport (session: {}): ClOrdID={}, OrigClOrdID={}, ExecType=REPLACED, OrderQty={}", 
                sessionID, clOrdId, origClOrdId, orderQty);
        } catch (Exception e) {
            log.error("Error sending Replace ExecutionReport: {}", e.getMessage(), e);
        }
    }

    /**
     * Send a filled ExecutionReport for a short sell order.
     * Side=SELL_SHORT (5), ExecType=FILL (2), OrdStatus=FILLED (2)
     */
    public void sendShortSellFilledExecutionReport(SessionID sessionID, String clOrdId, String symbol, 
                                                     double orderQty, double price, String account) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send Short Sell Filled ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            ExecutionReport report = new ExecutionReport(
                new OrderID("SIM-SHORT-" + System.currentTimeMillis()),
                new ExecID("EXEC-SHORT-" + System.currentTimeMillis()),
                new ExecTransType(ExecTransType.NEW),
                new ExecType(ExecType.FILL),
                new OrdStatus(OrdStatus.FILLED),
                new Symbol(symbol),
                new Side(Side.SELL_SHORT),
                new LeavesQty(0.0),
                new CumQty(orderQty),
                new AvgPx(price)
            );

            if (clOrdId != null && !clOrdId.isEmpty()) {
                report.setString(ClOrdID.FIELD, clOrdId);
            }
            report.setString(Symbol.FIELD, symbol);
            report.set(new OrderQty(orderQty));
            report.set(new LastPx(price));
            report.set(new LastShares(orderQty));
            if (account != null && !account.isEmpty()) {
                report.setString(Account.FIELD, account);
            }
            // Set LastMkt (tag 30) - valid in ExecutionReport messages
            // Tag 30 represents the market/route where the execution occurred
            report.setString(30, "ARCA"); // Tag 30 = LastMkt in ExecutionReport
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent Short Sell Filled ExecutionReport (session: {}): OrderID={}, ClOrdID={}, Symbol={}, Qty={}, Price={}", 
                sessionID, report.getString(OrderID.FIELD), clOrdId, symbol, orderQty, price);
        } catch (Exception e) {
            log.error("Error sending Short Sell Filled ExecutionReport: {}", e.getMessage(), e);
        }
    }

    /**
     * Send a filled ExecutionReport for a long buy order.
     * Side=BUY (1), ExecType=FILL (2), OrdStatus=FILLED (2)
     */
    public void sendLongBuyFilledExecutionReport(SessionID sessionID, String clOrdId, String symbol, 
                                                  double orderQty, double price, String account) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send Long Buy Filled ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            ExecutionReport report = new ExecutionReport(
                new OrderID("SIM-LONG-" + System.currentTimeMillis()),
                new ExecID("EXEC-LONG-" + System.currentTimeMillis()),
                new ExecTransType(ExecTransType.NEW),
                new ExecType(ExecType.FILL),
                new OrdStatus(OrdStatus.FILLED),
                new Symbol(symbol),
                new Side(Side.BUY),
                new LeavesQty(0.0),
                new CumQty(orderQty),
                new AvgPx(price)
            );

            if (clOrdId != null && !clOrdId.isEmpty()) {
                report.setString(ClOrdID.FIELD, clOrdId);
            }
            report.setString(Symbol.FIELD, symbol);
            report.set(new OrderQty(orderQty));
            report.set(new LastPx(price));
            report.set(new LastShares(orderQty));
            if (account != null && !account.isEmpty()) {
                report.setString(Account.FIELD, account);
            }
            // Set LastMkt (tag 30) - valid in ExecutionReport messages
            // Tag 30 represents the market/route where the execution occurred
            report.setString(30, "ARCA"); // Tag 30 = LastMkt in ExecutionReport
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent Long Buy Filled ExecutionReport (session: {}): OrderID={}, ClOrdID={}, Symbol={}, Qty={}, Price={}", 
                sessionID, report.getString(OrderID.FIELD), clOrdId, symbol, orderQty, price);
        } catch (Exception e) {
            log.error("Error sending Long Buy Filled ExecutionReport: {}", e.getMessage(), e);
        }
    }

    /**
     * Send a filled ExecutionReport for a short locate order.
     * Based on production logs: Side=BUY (1), ExecType=FILL (2), OrdStatus=FILLED (2)
     * Short locate orders are BUY orders with ExDestination set to locate route (e.g., TESTSL)
     * This represents a filled locate execution report, not a new order.
     */
    public void sendShortLocateNewOrderExecutionReport(SessionID sessionID, String clOrdId, String quoteReqId, 
                                                        String symbol, double orderQty, String account) {
        try {
            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.warn("Cannot send Short Locate Filled ExecutionReport: session {} is not logged on", sessionID);
                return;
            }

            // Generate OrderID and ExecID based on ClOrdID pattern (LOC-XXX-1)
            String orderId = clOrdId != null && clOrdId.startsWith("LOC-") 
                ? clOrdId.replace("LOC-", "").split("-")[0] 
                : String.valueOf(System.currentTimeMillis() % 10000);
            String execId = clOrdId != null && !clOrdId.isEmpty() ? clOrdId : "EXEC-LOCATE-" + System.currentTimeMillis();
            
            // Short locate filled: Side=BUY, ExecType=FILL, OrdStatus=FILLED
            ExecutionReport report = new ExecutionReport(
                new OrderID(orderId),
                new ExecID(execId),
                new ExecTransType(ExecTransType.NEW),
                new ExecType(ExecType.FILL),
                new OrdStatus(OrdStatus.FILLED),
                new Symbol(symbol),
                new Side(Side.BUY), // Short locate orders are BUY orders
                new LeavesQty(0.0), // Filled orders have 0 leaves
                new CumQty(orderQty),
                new AvgPx(0.01) // Typical locate price from production logs
            );

            if (clOrdId != null && !clOrdId.isEmpty()) {
                report.setString(ClOrdID.FIELD, clOrdId);
            }
            // QuoteReqID (tag 131) is NOT valid in ExecutionReport messages in FIX 4.2
            // It's only used in QuoteRequest/QuoteResponse messages
            // Do NOT set QuoteReqID in ExecutionReport - it causes rejection
            // if (quoteReqId != null && !quoteReqId.isEmpty()) {
            //     report.setString(QuoteReqID.FIELD, quoteReqId);
            // }
            report.setString(Symbol.FIELD, symbol);
            report.set(new OrderQty(orderQty));
            report.set(new LastPx(0.01)); // Typical locate execution price
            report.set(new LastShares(orderQty));
            if (account != null && !account.isEmpty()) {
                report.setString(Account.FIELD, account);
            }
            // Set LastMkt (tag 30) - valid in ExecutionReport messages
            // For locate orders, use TESTSL as the route
            report.setString(30, "TESTSL"); // Tag 30 = LastMkt in ExecutionReport
            report.set(new TransactTime());

            session.send(report);
            log.info("Simulator sent Short Locate Filled ExecutionReport (session: {}): OrderID={}, ClOrdID={}, QuoteReqID={}, Symbol={}, Qty={}", 
                sessionID, report.getString(OrderID.FIELD), clOrdId, quoteReqId, symbol, orderQty);
        } catch (Exception e) {
            log.error("Error sending Short Locate Filled ExecutionReport: {}", e.getMessage(), e);
        }
    }
}

