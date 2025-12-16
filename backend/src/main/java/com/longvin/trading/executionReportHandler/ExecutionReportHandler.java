package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import quickfix.SessionID;


public interface ExecutionReportHandler {

    boolean supports(ExecutionReportContext context);

    void handle(ExecutionReportContext context, SessionID sessionID);
}