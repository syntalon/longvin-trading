package com.longvin.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration to ensure log directory exists and is writable.
 * This is especially important on Azure VM where the directory might not exist.
 */
@Component
public class LoggingConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LoggingConfiguration.class);
    
    @EventListener(ApplicationReadyEvent.class)
    public void ensureLogDirectoryExists() {
        String logDir = System.getenv("LOG_DIR");
        if (logDir == null) {
            logDir = System.getProperty("LOG_DIR");
        }
        if (logDir == null) {
            logDir = "logs"; // Default relative path
        }
        
        try {
            Path logPath = Paths.get(logDir);
            if (!Files.exists(logPath)) {
                Files.createDirectories(logPath);
                log.info("Created log directory: {}", logPath.toAbsolutePath());
            } else if (!Files.isDirectory(logPath)) {
                log.error("Log path exists but is not a directory: {}", logPath.toAbsolutePath());
                return;
            }
            
            // Check if directory is writable
            if (!Files.isWritable(logPath)) {
                log.warn("Log directory exists but is not writable: {}", logPath.toAbsolutePath());
                log.warn("Please check directory permissions. Log files may not be written.");
            } else {
                log.info("Log directory is ready: {}", logPath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("Failed to create or verify log directory: {}", logDir, e);
            log.error("Log files may not be written. Please create the directory manually or check permissions.");
        }
    }
}

