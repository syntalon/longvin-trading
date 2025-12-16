package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class ExecutionReportProcessor {
    private static final Logger log = LoggerFactory.getLogger(ExecutionReportProcessor.class);
    private final List<ExecutionReportHandler> handlers;

    public ExecutionReportProcessor(List<ExecutionReportHandler> handlers) {
        this.handlers = handlers;
    }

    public void process(Message executionReport, SessionID sessionID) {
        try {
            ExecutionReportContext context = new ExecutionReportContext(executionReport);

            for (ExecutionReportHandler handler : handlers) {
                if (handler.supports(context)) {
                    log.debug("Using handler: {} for ClOrdID: {}",
                            handler.getClass().getSimpleName(), context.getClOrdID());


                    CompletableFuture.runAsync(() -> {
                        try {
                            handler.handle(context, sessionID);
                        } catch (Exception e) {
                            log.error("Error in handler: " + handler.getClass().getSimpleName(), e);
                        }
                    });

                    break;
                }
            }

            if (handlers.stream().noneMatch(h -> h.supports(context))) {
                log.warn("No handler found for Execution Report. ClOrdID: {}, ExecType: {}, OrdStatus: {}",
                        context.getClOrdID(), context.getExecType(), context.getOrdStatus());
            }

        } catch (FieldNotFound e) {
            log.error("Error parsing Execution Report", e);
        }
    }
}