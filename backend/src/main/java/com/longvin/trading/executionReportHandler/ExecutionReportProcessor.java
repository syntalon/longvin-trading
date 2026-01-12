package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;

import java.util.*;
import java.util.concurrent.Executor;

/**
 * Central processor for ExecutionReport messages.
 * Routes messages to appropriate handlers based on their support criteria.
 * Handlers are sorted by priority and executed asynchronously.
 */
@Component
public class ExecutionReportProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(ExecutionReportProcessor.class);
    
    private final List<ExecutionReportHandler> handlers;
    private final Executor executor;

    public ExecutionReportProcessor(List<ExecutionReportHandler> handlers,
                                    @Qualifier("orderMirroringExecutor") Executor executor) {
        this.handlers = handlers;
        this.executor = executor;
    }

    /**
     * Process an ExecutionReport message.
     * Finds ALL matching handlers and executes them asynchronously.
     * This approach ensures event-driven processing rather than priority-based processing.
     */
    public void process(Message executionReport, SessionID sessionID) {
        ExecutionReportContext context;
        try {
            context = new ExecutionReportContext(executionReport);
        } catch (FieldNotFound e) {
            log.error("Error parsing ExecutionReport: missing required field {}", e.field, e);
            return;
        }

        log.info("Processing ExecutionReport: ClOrdID={}, ExecType={}, OrdStatus={}, Symbol={}",
                context.getClOrdID(), context.getExecType(), context.getOrdStatus(), context.getSymbol());

        // Find ALL matching handlers instead of just the first one
        List<ExecutionReportHandler> matchedHandlers = new ArrayList<>();
        for (ExecutionReportHandler handler : handlers) {
            if (handler.supports(context)) {
                matchedHandlers.add(handler);
            }
        }

        if (matchedHandlers.isEmpty()) {
            log.warn("No handler found for ExecutionReport: ClOrdID={}, ExecType={}, OrdStatus={}",
                    context.getClOrdID(), context.getExecType(), context.getOrdStatus());
            return;
        }

        // Execute ALL matching handlers asynchronously using configured thread pool
        final ExecutionReportContext ctx = context;
        
        for (ExecutionReportHandler handler : matchedHandlers) {
            final ExecutionReportHandler h = handler;
            executor.execute(() -> {
                try {
                    log.info("Dispatching to {}: ClOrdID={}",
                            handler.getClass().getSimpleName(), ctx.getClOrdID());
                    h.handle(ctx, sessionID);
                } catch (Exception e) {
                    log.error("Error in handler {}: ClOrdID={}, Error={}", 
                            handler.getClass().getSimpleName(), ctx.getClOrdID(), e.getMessage(), e);
                }
            });
        }
    }
}