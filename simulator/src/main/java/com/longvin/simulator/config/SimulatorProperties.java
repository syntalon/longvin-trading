package com.longvin.simulator.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "simulator")
public class SimulatorProperties {

    @Getter
    private final boolean enabled;

    @Getter
    private final AcceptorConfig acceptor;

    @Getter
    private final InitiatorConfig initiator;

    @Getter
    private final boolean guiEnabled;

    public SimulatorProperties(
            @DefaultValue("true") boolean enabled,
            AcceptorConfig acceptor,
            InitiatorConfig initiator,
            @DefaultValue("true") boolean guiEnabled) {
        this.enabled = enabled;
        this.acceptor = acceptor != null ? acceptor : new AcceptorConfig(true, 8661, "OPAL", "OS111");
        this.initiator = initiator != null ? initiator : new InitiatorConfig(true, "localhost", 9877, "DAST", "OS111", 30);
        this.guiEnabled = guiEnabled;
    }

    public static class AcceptorConfig {
        @Getter
        private final boolean enabled;
        @Getter
        private final int port;
        @Getter
        private final String senderCompId;
        @Getter
        private final String targetCompId;

        public AcceptorConfig(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("8661") int port,
                @DefaultValue("OPAL") String senderCompId,
                @DefaultValue("OS111") String targetCompId) {
            this.enabled = enabled;
            this.port = port;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
        }
    }

    public static class InitiatorConfig {
        @Getter
        private final boolean enabled;
        @Getter
        private final String host;
        @Getter
        private final int port;
        @Getter
        private final String senderCompId;
        @Getter
        private final String targetCompId;
        @Getter
        private final int heartbeatInterval;

        public InitiatorConfig(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("localhost") String host,
                @DefaultValue("9877") int port,
                @DefaultValue("DAST") String senderCompId,
                @DefaultValue("OS111") String targetCompId,
                @DefaultValue("30") int heartbeatInterval) {
            this.enabled = enabled;
            this.host = host;
            this.port = port;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.heartbeatInterval = heartbeatInterval;
        }
    }
}

