package com.longvin.dasgateway.cmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.longvin.dasgateway.servicebus.ServiceBusProperties;

import java.time.Duration;

@Component
@EnableConfigurationProperties({CmdApiProperties.class, ServiceBusProperties.class})
public class CmdStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CmdStartup.class);

    private final CmdClient client;

    public CmdStartup(CmdClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            client.connect(Duration.ofSeconds(5));
            client.login();
        } catch (Exception e) {
            log.error("Failed to start CMD connection: {}", e.toString(), e);
        }
    }
}
