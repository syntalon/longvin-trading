package com.longvin.trading.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {
    @Bean(name = "orderMirroringExecutor")
    public Executor orderMirroringExecutor(@Value("${mirror.order.threadPoolSize:8}") int threadPoolSize) {
        return Executors.newFixedThreadPool(threadPoolSize);
    }
}

