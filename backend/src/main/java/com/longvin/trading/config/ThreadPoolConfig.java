package com.longvin.trading.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ThreadPoolConfig {
    
    @Bean(name = "orderMirroringExecutor")
    public Executor orderMirroringExecutor(@Value("${mirror.order.threadPoolSize:8}") int threadPoolSize) {
        return Executors.newFixedThreadPool(threadPoolSize);
    }
    
    /**
     * Scheduled executor service for the initiator logon guard.
     * Used to schedule resume of the initiator session after non-trading day pauses.
     * Spring will automatically call shutdown() when the application context is destroyed.
     */
    @Bean(name = "initiatorLogonGuardScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService initiatorLogonGuardScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "initiator-logon-guard");
                t.setDaemon(true);
                return t;
            });
    }
}

