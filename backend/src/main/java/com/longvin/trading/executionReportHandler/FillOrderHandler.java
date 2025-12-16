package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

@Component
public class FillOrderHandler implements ExecutionReportHandler {
    private static final Logger log = LoggerFactory.getLogger(FillOrderHandler.class);
    @Override
    public boolean supports(ExecutionReportContext context) {
        return (context.getExecType() == '2' && context.getOrdStatus() == '2') ||  // Filled
                (context.getExecType() == '1' && context.getOrdStatus() == '1');   // Partially Filled
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        if (context.getExecType() == '2') { // 完全成交
            log.info("Order completely filled. ClOrdID: {}, AvgPx: {}",
                    context.getClOrdID(), context.getAvgPx());
            //updateLocalOrderStatus(context.getClOrdID(), "FILLED");
        } else { // 部分成交
            log.info("Order partially filled. ClOrdID: {}, CumQty: {}/{}, LastPx: {}",
                    context.getClOrdID(),
                    context.getCumQty(), context.getOrderQty(),
                    context.getAvgPx());
            //updateLocalOrderStatus(context.getClOrdID(), "PARTIALLY_FILLED");
        }

        // 记录成交信息
        recordFillInformation(context);
    }

    private void recordFillInformation(ExecutionReportContext context) {
/*        FillRecord fillRecord = new FillRecord();
        fillRecord.setClOrdID(context.getClOrdID());
        fillRecord.setSymbol(context.getSymbol());
        fillRecord.setFillQuantity(context.getCumQty() - (context.getCumQty() - context.getLeavesQty()));
        fillRecord.setFillPrice(context.getAvgPx());
        fillRecord.setTimestamp(new Date());*/
        // 保存成交记录
    }
}