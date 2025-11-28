package com.longvin.trading.fix;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterators;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.longvin.trading.config.FixClientProperties;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.Dictionary;
import quickfix.FileStoreFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

@Component
@Slf4j
public class FixSessionManager implements SmartLifecycle {

    private final OrderReplicationCoordinator application;
    private final FixClientProperties properties;
    private final ResourceLoader resourceLoader;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private SocketInitiator initiator;
    private SocketAcceptor acceptor;
    private final AtomicBoolean initiatorPaused = new AtomicBoolean(false);

    public FixSessionManager(OrderReplicationCoordinator application,
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
                SessionSettings allSettings = loadSettings();
                SessionSettings acceptorSettings = filterSettings(allSettings, "acceptor");
                SessionSettings initiatorSettings = filterSettings(allSettings, "initiator");

                boolean startedSomething = false;
                MessageFactory messageFactory = new DefaultMessageFactory();

                if (hasSessions(acceptorSettings)) {
                    // Use FileStoreFactory for acceptor sessions so sequence numbers persist
                    // This ensures our sender sequence numbers match what DAS Trader expects
                    MessageStoreFactory storeFactory = new FileStoreFactory(acceptorSettings);
                    acceptor = new SocketAcceptor(application, storeFactory, acceptorSettings, null, messageFactory);
                    acceptor.start();
                    startedSomething = true;
                    log.info("FIX acceptor started for drop-copy sessions (using FileStore for sequence number persistence): {}", describeSessions(acceptorSettings));
                }

                if (hasSessions(initiatorSettings)) {
                    // Log the HeartBtInt setting to verify it's being used
                    try {
                        Properties defaults = initiatorSettings.getDefaultProperties();
                        if (defaults != null && defaults.containsKey("HeartBtInt")) {
                            log.info("FIX initiator HeartBtInt from DEFAULT: {} seconds", defaults.getProperty("HeartBtInt"));
                        }
                        Iterator<SessionID> sessionIterator = initiatorSettings.sectionIterator();
                        while (sessionIterator.hasNext()) {
                            SessionID sessionID = sessionIterator.next();
                            Properties sessionProps = initiatorSettings.getSessionProperties(sessionID);
                            if (sessionProps != null && sessionProps.containsKey("HeartBtInt")) {
                                log.info("FIX initiator session {} HeartBtInt: {} seconds", sessionID, sessionProps.getProperty("HeartBtInt"));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not log HeartBtInt settings: {}", e.getMessage());
                    }
                    MessageStoreFactory storeFactory = new MemoryStoreFactory();
                    initiator = new SocketInitiator(application, storeFactory, initiatorSettings, null, messageFactory);
                    initiator.start();
                    startedSomething = true;
                    log.info("FIX initiator started. Order-entry sessions: {}", describeSessions(initiatorSettings));
                }

                if (!startedSomething) {
                    running.set(false);
                    log.warn("No FIX sessions configured in {}. Nothing was started.", properties.getConfigPath());
                }
            } catch (IOException | RuntimeError | ConfigError e) {
                running.set(false);
                throw new IllegalStateException("Unable to start QuickFIX/J sessions", e);
            }
        }
    }

    public void pauseInitiator(String reason) {
        if (initiator == null) {
            log.warn("Cannot pause initiator: initiator is null");
            return;
        }
        if (initiatorPaused.compareAndSet(false, true)) {
            log.warn("Pausing FIX initiator: {} - calling initiator.stop() to prevent reconnection attempts", reason);
            try {
                initiator.stop();
                log.info("FIX initiator paused successfully - it will NOT attempt to reconnect until resumeInitiatorIfPaused() is called");
            } catch (Exception e) {
                log.warn("Error while pausing FIX initiator: {}", e.getMessage());
                initiatorPaused.set(false);
            }
        } else {
            log.debug("Initiator pause requested but initiator is already paused");
        }
    }

    public void resumeInitiatorIfPaused() {
        if (initiator == null) {
            log.warn("Cannot resume initiator: initiator is null");
            return;
        }
        if (initiatorPaused.compareAndSet(true, false)) {
            try {
                log.info("Resuming FIX initiator after pause - calling initiator.start()");
                initiator.start();
                log.info("FIX initiator resumed successfully - it will now attempt to reconnect automatically");
            } catch (ConfigError | RuntimeError e) {
                initiatorPaused.set(true);
                log.error("Unable to resume FIX initiator", e);
            }
        } else {
            log.debug("Initiator resume requested but initiator is not paused (current state: paused={})", initiatorPaused.get());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (initiator != null) {
                log.info("Stopping FIX initiator");
                initiator.stop();
                initiatorPaused.set(false);
            }
            if (acceptor != null) {
                log.info("Stopping FIX acceptor");
                acceptor.stop();
            }
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

    private SessionSettings filterSettings(SessionSettings source, String connectionType) throws ConfigError {
        SessionSettings filtered = new SessionSettings();
        copyDefaultProperties(source, filtered);
        Iterator<SessionID> iterator = source.sectionIterator();
        while (iterator.hasNext()) {
            SessionID sessionID = iterator.next();
            Properties props = source.getSessionProperties(sessionID);
            String type = props.getProperty("ConnectionType");
            if (connectionType.equalsIgnoreCase(type)) {
                filtered.set(sessionID, toDictionary(props));
            }
        }
        return filtered;
    }

    private void copyDefaultProperties(SessionSettings source, SessionSettings target) throws ConfigError {
        Properties defaults = source.getDefaultProperties();
        if (defaults == null || defaults.isEmpty()) {
            return;
        }
        target.set(toDictionary(defaults));
    }

    private Dictionary toDictionary(Properties properties) {
        Dictionary dictionary = new Dictionary();
        properties.forEach((key, value) -> dictionary.setString((String) key, (String) value));
        return dictionary;
    }

    private boolean hasSessions(SessionSettings settings) {
        return settings.sectionIterator().hasNext();
    }

    private String describeSessions(SessionSettings settings) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(settings.sectionIterator(), 0), false)
                .map(SessionID::toString)
                .collect(Collectors.joining(", "));
    }
}
