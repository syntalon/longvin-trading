package com.longvin.dasgateway.cmd;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class CmdClient {
    private static final Logger log = LoggerFactory.getLogger(CmdClient.class);

    private final CmdApiProperties props;
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "das-cmd-reader");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private volatile BufferedWriter writer;

    private final CopyOnWriteArrayList<CmdEventListener> listeners = new CopyOnWriteArrayList<>();

    public CmdClient(CmdApiProperties props) {
        this.props = props;
    }

    public void addListener(CmdEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CmdEventListener listener) {
        listeners.remove(listener);
    }

    private void fireConnected() {
        for (CmdEventListener l : listeners) {
            try {
                l.onConnected();
            } catch (Exception ignored) {
            }
        }
    }

    private void fireDisconnected() {
        for (CmdEventListener l : listeners) {
            try {
                l.onDisconnected();
            } catch (Exception ignored) {
            }
        }
    }

    private void fireInboundLine(String line) {
        for (CmdEventListener l : listeners) {
            try {
                l.onInboundLine(line);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireOutboundLine(String line) {
        for (CmdEventListener l : listeners) {
            try {
                l.onOutboundLine(line);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void connect(Duration timeout) throws IOException {
        if (isConnected()) {
            return;
        }

        Socket s = new Socket();
        s.connect(new InetSocketAddress(props.host(), props.port()), (int) timeout.toMillis());
        this.socket = s;
        this.writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        readerExecutor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        log.info("DAS<- {}", line);
                        fireInboundLine(line);
                    }
                }
            } catch (Exception e) {
                log.warn("CMD reader stopped: {}", e.toString());
            } finally {
                disconnectQuietly();
            }
        });

        log.info("Connected to DAS CMD API {}:{}", props.host(), props.port());
        fireConnected();
    }

    public synchronized void login() throws IOException {
        ensureConnected();

        String watchFlag = props.watch() ? "1" : "0";
        // Format: LOGIN Trader Password Account 1/0
        sendRaw("LOGIN %s %s %s %s".formatted(props.trader(), props.password(), props.account(), watchFlag));
    }

    public synchronized void sendRaw(String command) throws IOException {
        ensureConnected();

        // DAS uses line-oriented commands.
        writer.write(command);
        writer.write("\r\n");
        writer.flush();
        log.info("DAS-> {}", command);
        fireOutboundLine(command);
    }

    public synchronized void disconnectQuietly() {
        boolean wasConnected = isConnected();
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        writer = null;
        socket = null;

        if (wasConnected) {
            fireDisconnected();
        }
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to DAS CMD API");
        }
    }

    @PreDestroy
    public void shutdown() {
        disconnectQuietly();
        readerExecutor.shutdownNow();
    }
}
