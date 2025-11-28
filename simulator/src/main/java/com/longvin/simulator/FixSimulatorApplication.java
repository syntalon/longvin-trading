package com.longvin.simulator;

import com.longvin.simulator.config.SimulatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class FixSimulatorApplication {

    public static void main(String[] args) {
        // Disable headless mode to allow GUI
        // This must be set before any AWT/Swing classes are loaded
        System.setProperty("java.awt.headless", "false");
        
        SpringApplication.run(FixSimulatorApplication.class, args);
    }

}

