package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.fixSender.FixMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

@Component
public class NewOrderHandler implements ExecutionReportHandler {
    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);
    private final FixMessageSender fixMessageSender;

    public NewOrderHandler(FixMessageSender fixMessageSender) {
        this.fixMessageSender = fixMessageSender;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '0' && context.getOrdStatus() == '0';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("Order submitted successfully. ClOrdID: {}, OrderID: {}",
                context.getClOrdID(), context.getOrderID());


        updateLocalOrderStatus(context.getClOrdID(), "SUBMITTED");


        recordOrderSubmission(context);
    }

    private void updateLocalOrderStatus(String clOrdID, String status) {

    }

    private void recordOrderSubmission(ExecutionReportContext context) {
       /* OrderRecord record = new OrderRecord();
        record.setClOrdID(context.getClOrdID());
        record.setOrderID(context.getOrderID());
        record.setSymbol(context.getSymbol());
        record.setSide(context.getSide());
        record.setStatus("SUBMITTED");
        record.setTimestamp(new Date());*/
    }
}