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
        // 订单已提交成功
        log.info("Order submitted successfully. ClOrdID: {}, OrderID: {}",
                context.getClOrdID(), context.getOrderID());

        // 更新本地订单状态
        updateLocalOrderStatus(context.getClOrdID(), "SUBMITTED");

        // 记录订单信息
        recordOrderSubmission(context);
    }

    private void updateLocalOrderStatus(String clOrdID, String status) {
        // 更新数据库中的订单状态
        // 可以使用JPA Repository或MyBatis Mapper
    }

    private void recordOrderSubmission(ExecutionReportContext context) {
        // 记录订单提交日志
       /* OrderRecord record = new OrderRecord();
        record.setClOrdID(context.getClOrdID());
        record.setOrderID(context.getOrderID());
        record.setSymbol(context.getSymbol());
        record.setSide(context.getSide());
        record.setStatus("SUBMITTED");
        record.setTimestamp(new Date());*/
        // 保存到数据库
    }
}