package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * Handler for new order ExecutionReports (ExecType=0, OrdStatus=0).
 * 
 * When a new order is confirmed:
 * Logs the new order confirmation. Order replication is handled by FillOrderHandler when orders are filled.
 */
@Component
public class NewOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '0' && context.getOrdStatus() == '0';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("New order confirmed. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty());
    }
}