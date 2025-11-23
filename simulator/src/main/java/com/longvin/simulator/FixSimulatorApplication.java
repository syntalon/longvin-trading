package com.longvin.simulator;

import com.longvin.simulator.config.SimulatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class FixSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FixSimulatorApplication.class, args);
    }

}

