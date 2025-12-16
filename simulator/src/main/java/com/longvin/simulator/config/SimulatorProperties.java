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

    @Getter
    private final ShortLocateConfig shortLocate;

    public SimulatorProperties(
            @DefaultValue("true") boolean enabled,
            AcceptorConfig acceptor,
            InitiatorConfig initiator,
            @DefaultValue("true") boolean guiEnabled,
            ShortLocateConfig shortLocate) {
        this.enabled = enabled;
        this.acceptor = acceptor != null ? acceptor : new AcceptorConfig(true, 8661, "OPAL", "OS111");
        this.initiator = initiator != null ? initiator : new InitiatorConfig(true, "localhost", 9877, "DAST", "OS111", 30);
        this.guiEnabled = guiEnabled;
        this.shortLocate = shortLocate != null ? shortLocate : new ShortLocateConfig(true, "FULL", 0.03d, 0.0d, 0.5d);
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

    /**
     * Behavior controls for short locate quote/accept simulation:
     * - Quote Request (MsgType=R) -> Quote Response (MsgType=S)
     * - Locate Accept (MsgType=p) -> ExecutionReport with OrdStatus=B (CALCULATED)
     */
    public static class ShortLocateConfig {
        @Getter
        private final boolean enabled;
        /**
         * Supported values:
         * - FULL: OfferSize = requested qty
         * - RATIO: OfferSize = requested qty * ratio
         * - FIXED: OfferSize = fixedOfferSize
         * - ZERO: OfferSize = 0 (rejected)
         */
        @Getter
        private final String offerMode;
        @Getter
        private final double offerPx;
        @Getter
        private final double fixedOfferSize;
        @Getter
        private final double ratio;

        public ShortLocateConfig(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("FULL") String offerMode,
                @DefaultValue("0.03") double offerPx,
                @DefaultValue("0") double fixedOfferSize,
                @DefaultValue("0.5") double ratio) {
            this.enabled = enabled;
            this.offerMode = offerMode;
            this.offerPx = offerPx;
            this.fixedOfferSize = fixedOfferSize;
            this.ratio = ratio;
        }
    }
}

