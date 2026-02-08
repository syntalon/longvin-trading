package com.longvin.dasgateway.cmdpublish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.longvin.dasgateway.cmd.CmdClient;
import com.longvin.dasgateway.cmd.CmdEventListener;
import com.longvin.dasgateway.servicebus.ServiceBusSenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@EnableConfigurationProperties(CmdPublishProperties.class)
public class CmdOrderEventPublisher implements ApplicationRunner, CmdEventListener {

    private static final Logger log = LoggerFactory.getLogger(CmdOrderEventPublisher.class);

    private final CmdClient client;
    private final CmdPublishProperties publishProps;
    private final ServiceBusSenderService sender;
    private final ObjectMapper objectMapper;

    public CmdOrderEventPublisher(CmdClient client,
                                 CmdPublishProperties publishProps,
                                 ServiceBusSenderService sender,
                                 ObjectMapper objectMapper) {
        this.client = client;
        this.publishProps = publishProps;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        client.addListener(this);
    }

    @Override
    public void onInboundLine(String line) {
        publish("INBOUND", line);
    }

    @Override
    public void onOutboundLine(String line) {
        publish("OUTBOUND", line);
    }

    private void publish(String direction, String line) {
        if (!publishProps.enabled()) {
            return;
        }

        try {
            Map<String, Object> parsed = CmdOrderParser.parse(line);
            if (parsed == null) {
                return;
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("direction", direction);
            envelope.put("raw", line);
            envelope.putAll(parsed);

            String json = objectMapper.writeValueAsString(envelope);
            sender.send(json);
        } catch (Exception e) {
            log.warn("Failed to publish {} CMD line to Service Bus: {}", direction, e.toString());
        }
    }
}
