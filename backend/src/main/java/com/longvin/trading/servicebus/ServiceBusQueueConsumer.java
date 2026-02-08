package com.longvin.trading.servicebus;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(ServiceBusConsumerProperties.class)
@Order(10)
public class ServiceBusQueueConsumer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusQueueConsumer.class);

    private final ServiceBusConsumerProperties props;

    private volatile ServiceBusProcessorClient processor;

    public ServiceBusQueueConsumer(ServiceBusConsumerProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            log.info("Azure Service Bus consumer disabled (azure.servicebus.enabled=false)");
            return;
        }

        String authMode = props.authMode();
        if (authMode == null || authMode.isBlank()) {
            authMode = "connection-string";
        }

        ServiceBusClientBuilder builder;
        if ("managed-identity".equalsIgnoreCase(authMode) || "aad".equalsIgnoreCase(authMode)) {
            builder = new ServiceBusClientBuilder()
                    .fullyQualifiedNamespace(props.namespace())
                    .credential(new DefaultAzureCredentialBuilder().build());
        } else {
            builder = new ServiceBusClientBuilder()
                    .connectionString(props.connectionString());
        }

        ServiceBusProcessorClient p = builder
                .processor()
                .queueName(props.queueName())
                .maxConcurrentCalls(Math.max(1, props.maxConcurrentCalls()))
                .disableAutoComplete()
                .processMessage(this::onMessage)
                .processError(this::onError)
                .buildProcessorClient();

        this.processor = p;
        p.start();
        log.info("Azure Service Bus consumer started. authMode={}, queue={}", authMode, props.queueName());
    }

    private void onMessage(ServiceBusReceivedMessageContext context) {
        ServiceBusReceivedMessage msg = context.getMessage();

        try {
            String body;
            if (msg.getBody() != null) {
                body = msg.getBody().toString();
            } else {
                body = "";
            }

            log.info("SB message received. messageId={}, sequenceNumber={}, deliveryCount={}, body={}",
                    msg.getMessageId(), msg.getSequenceNumber(), msg.getDeliveryCount(), body);

            // TODO: replace with real business logic

            context.complete();
        } catch (IllegalArgumentException e) {
            // Treat as non-retryable / poison message
            log.warn("Non-retryable SB message. Dead-lettering. messageId={}, err={}", msg.getMessageId(), e.toString());
            String description = e.getMessage() != null ? e.getMessage() : e.toString();
            context.deadLetter(new DeadLetterOptions()
                    .setDeadLetterReason("NonRetryable")
                    .setDeadLetterErrorDescription(description));
        } catch (Exception e) {
            // Retryable: abandon so it can be retried; after max delivery count it goes to DLQ automatically.
            log.warn("Retryable SB message failure. Abandoning. messageId={}, err={}", msg.getMessageId(), e.toString(), e);
            context.abandon();
        }
    }

    private void onError(ServiceBusErrorContext errorContext) {
        log.error("Service Bus processor error. namespace={}, entityPath={}, err={}",
                errorContext.getFullyQualifiedNamespace(),
                errorContext.getEntityPath(),
                errorContext.getException().toString(),
                errorContext.getException());
    }

    @PreDestroy
    public void shutdown() {
        ServiceBusProcessorClient p = processor;
        if (p != null) {
            try {
                p.close();
            } catch (Exception e) {
                log.warn("Error closing Service Bus processor: {}", e.toString());
            }
        }
    }
}
