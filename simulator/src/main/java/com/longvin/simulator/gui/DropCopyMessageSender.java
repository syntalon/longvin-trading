package com.longvin.simulator.gui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Service for sending ExecutionReport (drop copy) messages through the simulator's initiator session.
 */
@Slf4j
@Component
public class DropCopyMessageSender {

    /**
     * Send an ExecutionReport message through the simulator's initiator session.
     * The session ID should be FIX.4.2:SIM-DAST->SIM-OS111
     */
    public boolean sendExecutionReport(ExecutionReportData data) {
        try {
            // Find the initiator session (SIM-DAST -> SIM-OS111)
            SessionID sessionID = findInitiatorSession();
            if (sessionID == null) {
                log.error("Cannot send ExecutionReport: initiator session not found");
                return false;
            }

            Session session = Session.lookupSession(sessionID);
            if (session == null || !session.isLoggedOn()) {
                log.error("Cannot send ExecutionReport: session {} is not logged on", sessionID);
                return false;
            }

            ExecutionReport report = buildExecutionReport(data);
            
            // Log the message before sending for debugging
            log.info("About to send ExecutionReport via GUI (session: {}): OrderID={}, ExecType={}, OrdStatus={}, Symbol={}, Side={}, Qty={}, Raw={}", 
                sessionID, data.getOrderId(), data.getExecType(), data.getOrdStatus(), data.getSymbol(), data.getSide(), data.getOrderQty(), report.toString());
            
            // Send the message
            boolean sent = session.send(report);
            
            if (sent) {
                log.info("Successfully queued ExecutionReport for sending (session: {}): OrderID={}, ExecType={}, Symbol={}, Side={}, Qty={}. " +
                    "Message is queued. To verify actual transmission: 1) Check QuickFIX messages log: target/quickfix/simulator/store/log/FIX.4.2-SIM-DAST-SIM-OS111.messages.log " +
                    "(look for your ExecutionReport) 2) Check backend logs for '[DROP COPY DEBUG] Received APP message' to confirm receipt.", 
                    sessionID, data.getOrderId(), data.getExecType(), data.getSymbol(), data.getSide(), data.getOrderQty());
            } else {
                log.warn("Failed to queue ExecutionReport for sending (session: {}): OrderID={}. Message was NOT queued - session may be disconnected or not logged on.", 
                    sessionID, data.getOrderId());
            }
            
            return sent;
        } catch (Exception e) {
            log.error("Error sending ExecutionReport: {}", e.getMessage(), e);
            return false;
        }
    }

    private SessionID findInitiatorSession() {
        // Construct the SessionID based on simulator configuration
        // Initiator session: SIM-DAST -> SIM-OS111
        SessionID sessionID = new SessionID("FIX.4.2", "SIM-DAST", "SIM-OS111");
        Session session = Session.lookupSession(sessionID);
        if (session != null) {
            return sessionID;
        }
        return null;
    }

    private ExecutionReport buildExecutionReport(ExecutionReportData data) throws FieldNotFound {
        // Generate IDs if not provided
        String orderId = data.getOrderId() != null && !data.getOrderId().isEmpty() 
            ? data.getOrderId() 
            : "ORD-" + System.currentTimeMillis();
        String execId = data.getExecId() != null && !data.getExecId().isEmpty()
            ? data.getExecId()
            : "EXEC-" + System.currentTimeMillis();

        // Build ExecutionReport with required fields
        ExecutionReport report = new ExecutionReport(
            new OrderID(orderId),
            new ExecID(execId),
            new ExecTransType(ExecTransType.NEW),
            new ExecType(data.getExecType().getFixValue()),
            new OrdStatus(data.getOrdStatus().getFixValue()),
            new Symbol(data.getSymbol()),
            new Side(data.getSide().getFixValue()),
            new LeavesQty(data.getLeavesQty() != null ? data.getLeavesQty().doubleValue() : 0.0),
            new CumQty(data.getCumQty() != null ? data.getCumQty().doubleValue() : 0.0),
            new AvgPx(data.getAvgPx() != null ? data.getAvgPx().doubleValue() : 0.0)
        );

        // Set optional fields
        // ClOrdID - set if provided, but don't force it (was working before without forcing)
        if (data.getClOrdId() != null && !data.getClOrdId().isEmpty()) {
            report.setString(ClOrdID.FIELD, data.getClOrdId());
        }
        if (data.getOrigClOrdId() != null && !data.getOrigClOrdId().isEmpty()) {
            report.setString(OrigClOrdID.FIELD, data.getOrigClOrdId());
        }
        if (data.getOrderQty() != null) {
            report.set(new OrderQty(data.getOrderQty().doubleValue()));
        }
        if (data.getPrice() != null) {
            report.set(new LastPx(data.getPrice().doubleValue()));
        }
        if (data.getLastQty() != null) {
            report.set(new LastShares(data.getLastQty().doubleValue()));
        }
        if (data.getAccount() != null && !data.getAccount().isEmpty()) {
            report.setString(Account.FIELD, data.getAccount());
        }
        if (data.getOrdType() != null) {
            report.set(new OrdType(data.getOrdType().getFixValue()));
        }
        if (data.getTimeInForce() != null) {
            report.set(new TimeInForce(data.getTimeInForce().getFixValue()));
        }
        if (data.getStopPx() != null) {
            report.set(new StopPx(data.getStopPx().doubleValue()));
        }

        // Set transaction time
        LocalDateTime transactTime = data.getTransactTime() != null 
            ? data.getTransactTime() 
            : LocalDateTime.now(ZoneOffset.UTC);
        report.set(new TransactTime(transactTime));

        if (data.getText() != null && !data.getText().isEmpty()) {
            report.setString(Text.FIELD, data.getText());
        }
        // QuoteReqID (tag 131) is NOT valid in ExecutionReport messages in FIX 4.2
        // It's only used in QuoteRequest/QuoteResponse messages
        // Do NOT set QuoteReqID in ExecutionReport - it causes rejection
        // if (data.getQuoteReqId() != null && !data.getQuoteReqId().isEmpty()) {
        //     report.setString(QuoteReqID.FIELD, data.getQuoteReqId());
        // }
        // ExDestination (tag 100) is NOT valid in ExecutionReport messages in FIX 4.2
        // It's only used in NewOrderSingle messages
        // Do NOT set ExDestination in ExecutionReport - it causes rejection
        // if (data.getExDestination() != null && !data.getExDestination().isEmpty()) {
        //     report.setString(ExDestination.FIELD, data.getExDestination());
        // }

        return report;
    }

    /**
     * Data class for ExecutionReport fields.
     */
    public static class ExecutionReportData {
        private String orderId;
        private String execId;
        private String clOrdId;
        private String origClOrdId;
        private String symbol;
        private ExecType execType;
        private OrdStatus ordStatus;
        private Side side;
        private OrdType ordType;
        private java.math.BigDecimal orderQty;
        private java.math.BigDecimal price;
        private java.math.BigDecimal lastQty;
        private java.math.BigDecimal leavesQty;
        private java.math.BigDecimal cumQty;
        private java.math.BigDecimal avgPx;
        private java.math.BigDecimal stopPx;
        private TimeInForce timeInForce;
        private String account;
        private LocalDateTime transactTime;
        private String text;
        private String quoteReqId;
        private String exDestination;

        // Enums matching FIX values
        public enum ExecType {
            NEW('0'), PARTIALLY_FILLED('1'), FILLED('2'), CANCELED('4'),
            REPLACED('5');

            private final char fixValue;
            ExecType(char fixValue) { this.fixValue = fixValue; }
            public char getFixValue() { return fixValue; }
        }

        public enum OrdStatus {
            NEW('0'), PARTIALLY_FILLED('1'), FILLED('2'), CANCELED('4'),
            REPLACED('5'), PENDING_CANCEL('6'), CALCULATED('B'), PENDING_REPLACE('E');

            private final char fixValue;
            OrdStatus(char fixValue) { this.fixValue = fixValue; }
            public char getFixValue() { return fixValue; }
        }

        public enum Side {
            BUY('1'), SELL('2'), SELL_SHORT('5'), SELL_SHORT_EXEMPT('6');

            private final char fixValue;
            Side(char fixValue) { this.fixValue = fixValue; }
            public char getFixValue() { return fixValue; }
        }

        public enum OrdType {
            MARKET('1'), LIMIT('2'), STOP('3'), STOP_LIMIT('4');

            private final char fixValue;
            OrdType(char fixValue) { this.fixValue = fixValue; }
            public char getFixValue() { return fixValue; }
        }

        public enum TimeInForce {
            DAY('0'), GTC('1'), OPG('2'), IOC('3'), FOK('4'), GTX('5'), GTD('6');

            private final char fixValue;
            TimeInForce(char fixValue) { this.fixValue = fixValue; }
            public char getFixValue() { return fixValue; }
        }

        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public String getExecId() { return execId; }
        public void setExecId(String execId) { this.execId = execId; }
        public String getClOrdId() { return clOrdId; }
        public void setClOrdId(String clOrdId) { this.clOrdId = clOrdId; }
        public String getOrigClOrdId() { return origClOrdId; }
        public void setOrigClOrdId(String origClOrdId) { this.origClOrdId = origClOrdId; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public ExecType getExecType() { return execType; }
        public void setExecType(ExecType execType) { this.execType = execType; }
        public OrdStatus getOrdStatus() { return ordStatus; }
        public void setOrdStatus(OrdStatus ordStatus) { this.ordStatus = ordStatus; }
        public Side getSide() { return side; }
        public void setSide(Side side) { this.side = side; }
        public OrdType getOrdType() { return ordType; }
        public void setOrdType(OrdType ordType) { this.ordType = ordType; }
        public java.math.BigDecimal getOrderQty() { return orderQty; }
        public void setOrderQty(java.math.BigDecimal orderQty) { this.orderQty = orderQty; }
        public java.math.BigDecimal getPrice() { return price; }
        public void setPrice(java.math.BigDecimal price) { this.price = price; }
        public java.math.BigDecimal getLastQty() { return lastQty; }
        public void setLastQty(java.math.BigDecimal lastQty) { this.lastQty = lastQty; }
        public java.math.BigDecimal getLeavesQty() { return leavesQty; }
        public void setLeavesQty(java.math.BigDecimal leavesQty) { this.leavesQty = leavesQty; }
        public java.math.BigDecimal getCumQty() { return cumQty; }
        public void setCumQty(java.math.BigDecimal cumQty) { this.cumQty = cumQty; }
        public java.math.BigDecimal getAvgPx() { return avgPx; }
        public void setAvgPx(java.math.BigDecimal avgPx) { this.avgPx = avgPx; }
        public java.math.BigDecimal getStopPx() { return stopPx; }
        public void setStopPx(java.math.BigDecimal stopPx) { this.stopPx = stopPx; }
        public TimeInForce getTimeInForce() { return timeInForce; }
        public void setTimeInForce(TimeInForce timeInForce) { this.timeInForce = timeInForce; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public LocalDateTime getTransactTime() { return transactTime; }
        public void setTransactTime(LocalDateTime transactTime) { this.transactTime = transactTime; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getQuoteReqId() { return quoteReqId; }
        public void setQuoteReqId(String quoteReqId) { this.quoteReqId = quoteReqId; }
        public String getExDestination() { return exDestination; }
        public void setExDestination(String exDestination) { this.exDestination = exDestination; }
    }
}

