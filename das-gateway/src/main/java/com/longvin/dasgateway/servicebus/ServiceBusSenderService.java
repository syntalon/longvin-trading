package com.longvin.dasgateway.servicebus;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ServiceBusSenderService {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusSenderService.class);

    private final ServiceBusProperties props;

    private volatile ServiceBusSenderClient sender;

    public ServiceBusSenderService(ServiceBusProperties props) {
        this.props = props;
    }

    public void send(String body) {
        if (!props.enabled()) {
            return;
        }
        if (props.queueName() == null || props.queueName().isBlank()) {
            throw new IllegalStateException("azure.servicebus.enabled=true but azure.servicebus.queue-name is empty. Provide AZURE_SERVICEBUS_QUEUE_NAME at startup.");
        }
        ServiceBusSenderClient s = getOrCreateSender();
        s.sendMessage(new ServiceBusMessage(body));
    }

    private synchronized ServiceBusSenderClient getOrCreateSender() {
        if (sender != null) {
            return sender;
        }

        String authMode = props.authMode();
        if (authMode == null || authMode.isBlank()) {
            authMode = "connection-string";
        }

        log.info("Creating Service Bus sender. authMode={}, queue={}", authMode, props.queueName());
        ServiceBusClientBuilder builder;
        if ("managed-identity".equalsIgnoreCase(authMode) || "aad".equalsIgnoreCase(authMode)) {
            builder = new ServiceBusClientBuilder()
                    .fullyQualifiedNamespace(props.namespace())
                    .credential(new DefaultAzureCredentialBuilder().build());
        } else {
            builder = new ServiceBusClientBuilder()
                    .connectionString(props.connectionString());
        }

        sender = builder
                .sender()
                .queueName(props.queueName())
                .buildClient();
        return sender;
    }

    @PreDestroy
    public void shutdown() {
        ServiceBusSenderClient s = sender;
        if (s != null) {
            s.close();
        }
    }
}
