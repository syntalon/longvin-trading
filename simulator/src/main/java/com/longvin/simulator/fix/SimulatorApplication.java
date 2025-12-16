package com.longvin.simulator.fix;

import com.longvin.simulator.service.ExecutionReportResponseService;
import com.longvin.simulator.service.LocateResponseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.ExecutionReport;
import quickfix.fix42.NewOrderSingle;
import quickfix.fix42.OrderCancelRequest;
import quickfix.fix42.OrderCancelReplaceRequest;

@Slf4j
@Component
public class SimulatorApplication implements Application {

    private final ExecutionReportResponseService executionReportService;
    private final LocateResponseService locateResponseService;

    public SimulatorApplication(ExecutionReportResponseService executionReportService,
                               LocateResponseService locateResponseService) {
        this.executionReportService = executionReportService;
        this.locateResponseService = locateResponseService;
    }

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
                    
                    // Automatically respond with ExecutionReport
                    executionReportService.sendNewOrderExecutionReport(sessionID, order);
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
            } else if ("L".equals(msgType)) {
                // LocateRequest
                try {
                    String locateReqId = message.isSetField(ClOrdID.FIELD) ? message.getString(ClOrdID.FIELD) : "N/A";
                    String symbol = message.isSetField(Symbol.FIELD) ? message.getString(Symbol.FIELD) : "N/A";
                    double qty = message.isSetField(OrderQty.FIELD) ? message.getDouble(OrderQty.FIELD) : 0.0;
                    log.info("Simulator received LocateRequest (session: {}): seqNum={}, LocateReqID={}, Symbol={}, Qty={}", 
                        sessionID, seqNum, locateReqId, symbol, qty);
                    
                    // Automatically respond with an approved locate response
                    locateResponseService.sendLocateResponse(sessionID, message);
                } catch (Exception e) {
                    log.error("Error parsing LocateRequest: {}", e.getMessage(), e);
                }
            } else if ("R".equals(msgType)) {
                // Short Locate Quote Request (DAS): MsgType=R, respond with MsgType=S
                try {
                    String quoteReqId = message.isSetField(QuoteReqID.FIELD) ? message.getString(QuoteReqID.FIELD)
                            : (message.isSetField(ClOrdID.FIELD) ? message.getString(ClOrdID.FIELD) : "N/A");
                    String symbol = message.isSetField(Symbol.FIELD) ? message.getString(Symbol.FIELD) : "N/A";
                    double qty = message.isSetField(OrderQty.FIELD) ? message.getDouble(OrderQty.FIELD) : 0.0;
                    log.info("Simulator received Short Locate Quote Request (MsgType=R) (session: {}): seqNum={}, QuoteReqID={}, Symbol={}, Qty={}",
                            sessionID, seqNum, quoteReqId, symbol, qty);
                    locateResponseService.sendShortLocateQuoteResponse(sessionID, message);
                } catch (Exception e) {
                    log.error("Error processing Short Locate Quote Request (MsgType=R)", e);
                }
            } else if ("p".equals(msgType)) {
                // Locate Accept (DAS): MsgType=p, respond with ExecutionReport OrdStatus=B
                try {
                    String quoteReqId = message.isSetField(QuoteReqID.FIELD) ? message.getString(QuoteReqID.FIELD)
                            : (message.isSetField(ClOrdID.FIELD) ? message.getString(ClOrdID.FIELD) : "N/A");
                    String symbol = message.isSetField(Symbol.FIELD) ? message.getString(Symbol.FIELD) : "N/A";
                    double qty = message.isSetField(OrderQty.FIELD) ? message.getDouble(OrderQty.FIELD) : 0.0;
                    log.info("Simulator received Locate Accept (MsgType=p) (session: {}): seqNum={}, QuoteReqID={}, Symbol={}, Qty={}",
                            sessionID, seqNum, quoteReqId, symbol, qty);
                    locateResponseService.sendLocateAcceptConfirmation(sessionID, message);
                } catch (Exception e) {
                    log.error("Error processing Locate Accept (MsgType=p)", e);
                }
            } else if ("F".equals(msgType)) {
                // OrderCancelRequest
                try {
                    OrderCancelRequest cancelRequest = new OrderCancelRequest();
                    cancelRequest.fromString(message.toString(), null, false);
                    String clOrdId = cancelRequest.isSetField(ClOrdID.FIELD) ? cancelRequest.getString(ClOrdID.FIELD) : "N/A";
                    String origClOrdId = cancelRequest.isSetField(OrigClOrdID.FIELD) ? cancelRequest.getString(OrigClOrdID.FIELD) : "N/A";
                    String symbol = cancelRequest.isSetField(Symbol.FIELD) ? cancelRequest.getString(Symbol.FIELD) : "N/A";
                    log.info("Simulator received OrderCancelRequest (session: {}): seqNum={}, ClOrdID={}, OrigClOrdID={}, Symbol={}", 
                        sessionID, seqNum, clOrdId, origClOrdId, symbol);
                    
                    // Automatically respond with canceled ExecutionReport
                    executionReportService.sendCancelExecutionReport(sessionID, cancelRequest);
                } catch (Exception e) {
                    log.error("Error parsing OrderCancelRequest: {}", e.getMessage(), e);
                }
            } else if ("G".equals(msgType)) {
                // OrderCancelReplaceRequest (Order Replace)
                try {
                    OrderCancelReplaceRequest replaceRequest = new OrderCancelReplaceRequest();
                    replaceRequest.fromString(message.toString(), null, false);
                    String clOrdId = replaceRequest.isSetField(ClOrdID.FIELD) ? replaceRequest.getString(ClOrdID.FIELD) : "N/A";
                    String origClOrdId = replaceRequest.isSetField(OrigClOrdID.FIELD) ? replaceRequest.getString(OrigClOrdID.FIELD) : "N/A";
                    String symbol = replaceRequest.isSetField(Symbol.FIELD) ? replaceRequest.getString(Symbol.FIELD) : "N/A";
                    double orderQty = replaceRequest.isSetField(OrderQty.FIELD) ? replaceRequest.getOrderQty().getValue() : 0.0;
                    log.info("Simulator received OrderCancelReplaceRequest (session: {}): seqNum={}, ClOrdID={}, OrigClOrdID={}, Symbol={}, OrderQty={}", 
                        sessionID, seqNum, clOrdId, origClOrdId, symbol, orderQty);
                    
                    // Automatically respond with replaced ExecutionReport
                    executionReportService.sendReplaceExecutionReport(sessionID, replaceRequest);
                } catch (Exception e) {
                    log.error("Error parsing OrderCancelReplaceRequest: {}", e.getMessage(), e);
                }
            } else {
                log.info("Simulator received application message (session: {}): msgType={}, seqNum={}", 
                    sessionID, msgType, seqNum);
            }
        } catch (Exception e) {
            log.error("Error processing incoming application message: {}", e.getMessage(), e);
        }
    }
}

