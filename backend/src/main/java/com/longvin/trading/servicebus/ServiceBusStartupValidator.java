package com.longvin.trading.servicebus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class ServiceBusStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusStartupValidator.class);

    private final ServiceBusConsumerProperties props;

    public ServiceBusStartupValidator(ServiceBusConsumerProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            return;
        }
        if (props.queueName() == null || props.queueName().isBlank()) {
            throw new IllegalStateException("azure.servicebus.enabled=true but azure.servicebus.queue-name is empty. Provide AZURE_SERVICEBUS_QUEUE_NAME.");
        }

        String authMode = props.authMode();
        if (authMode == null || authMode.isBlank()) {
            authMode = "connection-string";
        }

        if ("managed-identity".equalsIgnoreCase(authMode) || "aad".equalsIgnoreCase(authMode)) {
            String ns = props.namespace();
            if (ns == null || ns.isBlank()) {
                throw new IllegalStateException("azure.servicebus.enabled=true and azure.servicebus.auth-mode=managed-identity but azure.servicebus.namespace is empty. Provide AZURE_SERVICEBUS_NAMESPACE like 'dev-eastus-servicebus-main.servicebus.windows.net'.");
            }
            if (ns.contains("/") || ns.contains(";") || ns.contains(" ")) {
                throw new IllegalStateException("Azure Service Bus namespace must be a fully qualified namespace like 'xxx.servicebus.windows.net' (no scheme, slashes, or semicolons).");
            }
            if (!ns.endsWith(".servicebus.windows.net")) {
                throw new IllegalStateException("Azure Service Bus namespace should end with '.servicebus.windows.net'. Provided: " + ns);
            }
        } else if ("connection-string".equalsIgnoreCase(authMode) || "sas".equalsIgnoreCase(authMode)) {
            String cs = props.connectionString();
            if (cs == null || cs.isBlank()) {
                throw new IllegalStateException("azure.servicebus.enabled=true and azure.servicebus.auth-mode=connection-string but azure.servicebus.connection-string is empty. Provide AZURE_SERVICEBUS_CONNECTION_STRING.");
            }
            if (cs.contains("\\") || cs.contains("\n") || cs.contains("\r")) {
                throw new IllegalStateException("Azure Service Bus connection string appears to contain a backslash/newline. Ensure it is a single-line value like 'Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=...'.");
            }
            if (!cs.contains("Endpoint=sb://")) {
                throw new IllegalStateException("Azure Service Bus connection string must contain 'Endpoint=sb://...'. Check AZURE_SERVICEBUS_CONNECTION_STRING.");
            }
        } else {
            throw new IllegalStateException("Unsupported azure.servicebus.auth-mode: " + authMode + ". Use 'connection-string' or 'managed-identity'.");
        }

        log.info("Azure Service Bus consumer enabled. authMode={}, queue={}, maxConcurrentCalls={}", authMode, props.queueName(), props.maxConcurrentCalls());
    }
}
