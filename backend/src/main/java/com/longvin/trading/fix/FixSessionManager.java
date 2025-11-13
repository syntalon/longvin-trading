package com.longvin.trading.fix;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SLF4JLogFactory;
import quickfix.SocketInitiator;

@Component
public class FixSessionManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FixSessionManager.class);

    private final MirrorTradingApplication application;
    private final FixClientProperties properties;
    private final ResourceLoader resourceLoader;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private SocketInitiator initiator;

    public FixSessionManager(MirrorTradingApplication application,
                             FixClientProperties properties,
                             ResourceLoader resourceLoader) {
        this.application = Objects.requireNonNull(application, "application must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader must not be null");
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("FIX initiator disabled via configuration. Skipping startup.");
            return;
        }
        if (running.compareAndSet(false, true)) {
            try {
                SessionSettings settings = loadSettings();
                MessageStoreFactory storeFactory = new MemoryStoreFactory();
                LogFactory logFactory = new SLF4JLogFactory(settings);
                MessageFactory messageFactory = new DefaultMessageFactory();
                initiator = new SocketInitiator(application, storeFactory, settings, logFactory, messageFactory);
                initiator.start();
                String configuredSessions = StreamSupport.stream(
                                Spliterators.spliteratorUnknownSize(settings.sectionIterator(), 0), false)
                        .map(SessionID::toString)
                        .collect(Collectors.joining(", "));
                log.info("FIX initiator started. Primary session={}, shadows={}, configured sessions={}",
                        properties.getPrimarySession(), properties.getShadowSessions(), configuredSessions);
            } catch (IOException | RuntimeError | ConfigError e) {
                running.set(false);
                throw new IllegalStateException("Unable to start QuickFIX/J initiator", e);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && initiator != null) {
            log.info("Stopping FIX initiator");
            initiator.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private SessionSettings loadSettings() throws IOException, ConfigError {
        Resource resource = resourceLoader.getResource(properties.getConfigPath());
        if (!resource.exists()) {
            throw new IllegalStateException("FIX settings resource not found: " + properties.getConfigPath());
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return new SessionSettings(inputStream);
        }
    }
}
