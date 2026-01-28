package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * Handler for replaced order ExecutionReports (ExecType=5, OrdStatus=5).
 * 
 * A replaced order occurs when an order is modified (e.g., price or quantity changed).
 * This handler simply logs the replacement event but does not perform any actions.
 */
@Component
public class ReplacedOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(ReplacedOrderHandler.class);

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '5' && context.getOrdStatus() == '5';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // Log replaced order details
        // OrigClOrdID contains the original ClOrdID before replacement
        String origClOrdID = context.getOrigClOrdID() != null ? context.getOrigClOrdID() : "N/A";
        log.info("Order replaced. ClOrdID={}, OrigClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}, Account={}",
                context.getClOrdID(), origClOrdID, context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty(), context.getAccount());
    }
}

