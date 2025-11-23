package com.longvin.simulator.fix;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.NewOrderSingle;

@Slf4j
@Component
public class SimulatorApplication implements Application {

    @Override
    public void onCreate(SessionID sessionID) {
        log.info("Simulator session created: {}", sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        log.info("Simulator session logged on: {}", sessionID);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        log.info("Simulator session logged out: {}", sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if ("A".equals(msgType)) {
                // Logon message
                int heartBtInt = message.getInt(HeartBtInt.FIELD);
                int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
                log.info("Simulator sending Logon (session: {}): seqNum={}, HeartBtInt={} seconds", 
                    sessionID, seqNum, heartBtInt);
            } else if ("5".equals(msgType)) {
                log.info("Simulator sending Logout (session: {})", sessionID);
            } else if ("0".equals(msgType)) {
                int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
                log.debug("Simulator sending Heartbeat (session: {}): seqNum={}", sessionID, seqNum);
            }
        } catch (Exception e) {
            log.debug("Error processing outgoing admin message: {}", e.getMessage());
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if ("A".equals(msgType)) {
                // Logon message
                int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
                int heartBtInt = message.getInt(HeartBtInt.FIELD);
                boolean hasResetFlag = message.isSetField(ResetSeqNumFlag.FIELD) 
                    && message.getBoolean(ResetSeqNumFlag.FIELD);
                log.info("Simulator received Logon (session: {}): seqNum={}, HeartBtInt={} seconds, ResetSeqNumFlag={}", 
                    sessionID, seqNum, heartBtInt, hasResetFlag);
            } else if ("5".equals(msgType)) {
                String text = message.isSetField(Text.FIELD) ? message.getString(Text.FIELD) : "No reason";
                int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
                log.info("Simulator received Logout (session: {}): seqNum={}, reason={}", sessionID, seqNum, text);
            } else if ("0".equals(msgType)) {
                int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
                log.debug("Simulator received Heartbeat (session: {}): seqNum={}", sessionID, seqNum);
            }
        } catch (Exception e) {
            log.debug("Error processing incoming admin message: {}", e.getMessage());
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
            log.info("Simulator sending application message (session: {}): msgType={}, seqNum={}", 
                sessionID, msgType, seqNum);
        } catch (Exception e) {
            log.debug("Error processing outgoing application message: {}", e.getMessage());
        }
    }

    @Override
    public void fromApp(Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            int seqNum = message.getHeader().getInt(MsgSeqNum.FIELD);
            
            if ("D".equals(msgType)) {
                // NewOrderSingle
                try {
                    NewOrderSingle order = new NewOrderSingle();
                    order.fromString(message.toString(), null, false);
                    String clOrdId = order.isSetField(ClOrdID.FIELD) ? order.getString(ClOrdID.FIELD) : "N/A";
                    String symbol = order.isSetField(Symbol.FIELD) ? order.getString(Symbol.FIELD) : "N/A";
                    char side = order.isSetField(Side.FIELD) ? order.getSide().getValue() : '?';
                    double orderQty = order.isSetField(OrderQty.FIELD) ? order.getOrderQty().getValue() : 0.0;
                    log.info("Simulator received NewOrderSingle (session: {}): seqNum={}, ClOrdID={}, Symbol={}, Side={}, OrderQty={}", 
                        sessionID, seqNum, clOrdId, symbol, side, orderQty);
                    
                    // Simulate sending an ExecutionReport
                    sendExecutionReport(sessionID, order);
                } catch (Exception e) {
                    log.error("Error parsing NewOrderSingle: {}", e.getMessage(), e);
                }
            } else if ("8".equals(msgType)) {
                // ExecutionReport
                try {
                    ExecutionReport report = new ExecutionReport();
                    report.fromString(message.toString(), null, false);
                    String orderId = report.isSetField(OrderID.FIELD) ? report.getString(OrderID.FIELD) : "N/A";
                    char execType = report.isSetField(ExecType.FIELD) ? report.getExecType().getValue() : '?';
                    char ordStatus = report.isSetField(OrdStatus.FIELD) ? report.getOrdStatus().getValue() : '?';
                    log.info("Simulator received ExecutionReport (session: {}): seqNum={}, OrderID={}, ExecType={}, OrdStatus={}", 
                        sessionID, seqNum, orderId, execType, ordStatus);
                } catch (Exception e) {
                    log.error("Error parsing ExecutionReport: {}", e.getMessage(), e);
                }
            } else {
                log.info("Simulator received application message (session: {}): msgType={}, seqNum={}", 
                    sessionID, msgType, seqNum);
            }
        } catch (Exception e) {
            log.error("Error processing incoming application message: {}", e.getMessage(), e);
        }
    }

    private void sendExecutionReport(SessionID sessionID, NewOrderSingle order) {
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
            log.info("Simulator sent ExecutionReport (session: {}): OrderID={}, ExecType={}, OrdStatus={}", 
                sessionID, report.getString(OrderID.FIELD), ExecType.FILL, OrdStatus.FILLED);
        } catch (Exception e) {
            log.error("Error sending ExecutionReport: {}", e.getMessage(), e);
        }
    }
}

