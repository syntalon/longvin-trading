package com.longvin.trading.servicebus;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "azure.servicebus")
public record ServiceBusConsumerProperties(
        boolean enabled,
        String connectionString,
        String queueName,
        int maxConcurrentCalls
) {
}
