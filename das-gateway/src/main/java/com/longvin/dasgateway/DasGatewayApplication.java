package com.longvin.dasgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DasGatewayApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DasGatewayApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
