package com.longvin.dasgateway.servicebus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ServiceBusStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusStartupValidator.class);

    private final ServiceBusProperties props;

    public ServiceBusStartupValidator(ServiceBusProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            return;
        }

        if (props.connectionString() == null || props.connectionString().isBlank()) {
            throw new IllegalStateException("azure.servicebus.enabled=true but azure.servicebus.connection-string is empty. Provide AZURE_SERVICEBUS_CONNECTION_STRING at startup.");
        }
        if (props.queueName() == null || props.queueName().isBlank()) {
            throw new IllegalStateException("azure.servicebus.enabled=true but azure.servicebus.queue-name is empty. Provide AZURE_SERVICEBUS_QUEUE_NAME at startup.");
        }

        log.info("Azure Service Bus producer enabled. queue={}", props.queueName());
    }
}
