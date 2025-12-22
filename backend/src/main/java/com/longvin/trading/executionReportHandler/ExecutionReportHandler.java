package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import quickfix.SessionID;

/**
 * Interface for handling ExecutionReport messages.
 * Implementations should be registered as Spring beans and will be automatically
 * discovered and ordered by priority.
 */
public interface ExecutionReportHandler {

    /**
     * Check if this handler supports the given execution report context.
     * @param context The parsed execution report
     * @return true if this handler should process the report
     */
    boolean supports(ExecutionReportContext context);

    /**
     * Handle the execution report.
     * @param context The parsed execution report
     * @param sessionID The FIX session that received the report
     */
    void handle(ExecutionReportContext context, SessionID sessionID);

}