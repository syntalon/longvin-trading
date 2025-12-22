package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * Handler for fill ExecutionReports (ExecType=1 or 2).
 * 
 * Handles:
 * 1. Partial fills (ExecType=1)
 * 2. Full fills (ExecType=2)
 */
@Component
public class FillOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(FillOrderHandler.class);


    @Override
    public boolean supports(ExecutionReportContext context) {
        // Handle both partial fills (1) and full fills (2)
        return context.getExecType() == '1' || context.getExecType() == '2';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        if (context.getExecType() == '2') {
            log.info("Order completely filled. ClOrdID: {}, AvgPx: {}",
                    context.getClOrdID(), context.getAvgPx());
        } else {
            log.info("Order partially filled. ClOrdID: {}, CumQty: {}/{}, LastPx: {}",
                    context.getClOrdID(),
                    context.getCumQty(), context.getOrderQty(),
                    context.getAvgPx());
        }
        recordFillInformation(context);
    }

    private void recordFillInformation(ExecutionReportContext context) {
        // 在这里添加填充信息记录逻辑
        log.debug("Recording fill information for order: {}", context.getClOrdID());
    }
}