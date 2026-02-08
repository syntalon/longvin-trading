package com.longvin.dasgateway.servicebus;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
public class ServiceBusSenderService {

    private final ServiceBusProperties props;

    private volatile ServiceBusSenderClient sender;

    public ServiceBusSenderService(ServiceBusProperties props) {
        this.props = props;
    }

    public void send(String body) {
        if (!props.enabled()) {
            return;
        }
        ServiceBusSenderClient s = getOrCreateSender();
        s.sendMessage(new ServiceBusMessage(body));
    }

    private synchronized ServiceBusSenderClient getOrCreateSender() {
        if (sender != null) {
            return sender;
        }
        sender = new ServiceBusClientBuilder()
                .connectionString(props.connectionString())
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
