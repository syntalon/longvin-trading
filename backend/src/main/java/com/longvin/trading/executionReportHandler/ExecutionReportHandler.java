package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import quickfix.SessionID;

/**
 * Execution Report 处理器接口
 */
public interface ExecutionReportHandler {
    /**
     * 是否支持处理此类型的消息
     */
    boolean supports(ExecutionReportContext context);

    /**
     * 处理Execution Report
     */
    void handle(ExecutionReportContext context, SessionID sessionID);
}