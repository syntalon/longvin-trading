package com.longvin.simulator.fix;

import com.longvin.simulator.config.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import quickfix.*;

import java.io.InputStream;

@Slf4j
@Component
public class SimulatorSessionManager implements CommandLineRunner {

    private final SimulatorProperties properties;
    private final SimulatorApplication application;
    private SocketAcceptor acceptor;
    private SocketInitiator initiator;
    private final Object lock = new Object();
    private volatile boolean running = false;

    @Autowired
    public SimulatorSessionManager(SimulatorProperties properties, SimulatorApplication application) {
        this.properties = properties;
        this.application = application;
    }

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            log.info("FIX Simulator is disabled");
            return;
        }

        synchronized (lock) {
            if (running) {
                log.warn("FIX Simulator is already running");
                return;
            }
            running = true;
        }

        try {
            SessionSettings settings = loadSettings();
            MessageFactory messageFactory = new DefaultMessageFactory();

            // Start acceptor session (simulates OPAL server)
            if (properties.getAcceptor().isEnabled()) {
                startAcceptor(settings, messageFactory);
            }

            // Start initiator session (simulates DAS Trader)
            if (properties.getInitiator().isEnabled()) {
                startInitiator(settings, messageFactory);
            }

            log.info("FIX Simulator started successfully");
        } catch (Exception e) {
            log.error("Error starting FIX Simulator", e);
            running = false;
        }
    }

    private SessionSettings loadSettings() throws ConfigError {
        InputStream configStream = getClass().getClassLoader()
            .getResourceAsStream("fix/simulator.cfg");
        if (configStream == null) {
            throw new ConfigError("Could not find fix/simulator.cfg in classpath");
        }
        return new SessionSettings(configStream);
    }

    private void startAcceptor(SessionSettings allSettings, MessageFactory messageFactory) throws ConfigError {
        SessionSettings acceptorSettings = filterSettings(allSettings, "acceptor");
        if (acceptorSettings.size() == 0) {
            log.warn("No acceptor sessions found in configuration");
            return;
        }

        MessageStoreFactory storeFactory = new FileStoreFactory(acceptorSettings);
        acceptor = new SocketAcceptor(application, storeFactory, acceptorSettings, null, messageFactory);
        acceptor.start();
        log.info("FIX Simulator acceptor started: {}", describeSessions(acceptorSettings));
    }

    private void startInitiator(SessionSettings allSettings, MessageFactory messageFactory) throws ConfigError {
        SessionSettings initiatorSettings = filterSettings(allSettings, "initiator");
        if (initiatorSettings.size() == 0) {
            log.warn("No initiator sessions found in configuration");
            return;
        }

        MessageStoreFactory storeFactory = new FileStoreFactory(initiatorSettings);
        initiator = new SocketInitiator(application, storeFactory, initiatorSettings, null, messageFactory);
        initiator.start();
        log.info("FIX Simulator initiator started: {}", describeSessions(initiatorSettings));
    }

    private SessionSettings filterSettings(SessionSettings allSettings, String connectionType) throws ConfigError {
        SessionSettings filtered = new SessionSettings();
        
        // Copy [DEFAULT] section first - this is critical for StartTime, EndTime, etc.
        Dictionary defaultDict = allSettings.get();
        if (defaultDict != null) {
            filtered.set(defaultDict);
        }
        
        // Then copy matching session sections
        java.util.Iterator<SessionID> iterator = allSettings.sectionIterator();
        while (iterator.hasNext()) {
            SessionID sessionID = iterator.next();
            Dictionary sessionDict = allSettings.get(sessionID);
            try {
                String type = sessionDict.getString("ConnectionType");
                if (connectionType.equalsIgnoreCase(type)) {
                    filtered.set(sessionID, sessionDict);
                }
            } catch (FieldConvertError e) {
                log.debug("Could not read ConnectionType for session {}: {}", sessionID, e.getMessage());
            }
        }
        return filtered;
    }

    private String describeSessions(SessionSettings settings) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<SessionID> iterator = settings.sectionIterator();
        while (iterator.hasNext()) {
            SessionID sessionID = iterator.next();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(sessionID);
        }
        return sb.toString();
    }

    public void stop() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
        }

        try {
            if (acceptor != null) {
                acceptor.stop();
                log.info("FIX Simulator acceptor stopped");
            }
            if (initiator != null) {
                initiator.stop();
                log.info("FIX Simulator initiator stopped");
            }
        } catch (Exception e) {
            log.error("Error stopping FIX Simulator", e);
        }
    }
}

