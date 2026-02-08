package com.longvin.dasgateway.servicebus;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "azure.servicebus")
public record ServiceBusProperties(
        boolean enabled,
        String connectionString,
        String queueName
) {
}
