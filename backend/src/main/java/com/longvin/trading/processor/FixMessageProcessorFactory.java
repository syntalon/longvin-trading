package com.longvin.trading.processor;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.fix.FixSessionManager;
import com.longvin.trading.fix.InitiatorLogonGuard;
import com.longvin.trading.processor.impl.AcceptorMessageProcessor;
import com.longvin.trading.processor.impl.InitiatorMessageProcessor;
import com.longvin.trading.service.DropCopyReplicationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating FixMessageProcessor instances.
 * Processors are created on-demand rather than being Spring beans,
 * which eliminates circular dependency issues.
 */
@Component
public class FixMessageProcessorFactory {

    private final InitiatorLogonGuard logonGuard;
    private final FixSessionManager sessionManager;
    private final DropCopyReplicationService replicationService;
    private final FixClientProperties properties;

    public FixMessageProcessorFactory(InitiatorLogonGuard logonGuard,
                                      @Lazy FixSessionManager sessionManager,
                                      DropCopyReplicationService replicationService,
                                      FixClientProperties properties) {
        this.logonGuard = logonGuard;
        this.sessionManager = sessionManager;
        this.replicationService = replicationService;
        this.properties = properties;
    }

    /**
     * Get all processors that might handle the given session.
     * Processors are created fresh each time to avoid state issues.
     */
    public List<FixMessageProcessor> getProcessorsForSession(SessionID sessionID, String connectionType) {
        List<FixMessageProcessor> processors = new ArrayList<>();
        
        if ("initiator".equalsIgnoreCase(connectionType)) {
            processors.add(new InitiatorMessageProcessor(logonGuard, sessionManager));
        } else if ("acceptor".equalsIgnoreCase(connectionType)) {
            processors.add(new AcceptorMessageProcessor(replicationService, properties));
        }
        
        return processors;
    }

    /**
     * Get all processors that might handle the given session (fallback method).
     */
    public List<FixMessageProcessor> getProcessorsForSession(SessionID sessionID) {
        List<FixMessageProcessor> processors = new ArrayList<>();
        
        // Try both types - the processor's handlesSession() will determine if it matches
        processors.add(new InitiatorMessageProcessor(logonGuard, sessionManager));
        processors.add(new AcceptorMessageProcessor(replicationService, properties));
        
        return processors;
    }
}

