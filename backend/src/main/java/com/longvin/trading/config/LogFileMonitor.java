package com.longvin.trading.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Monitors log file appender status and reports errors when file logging fails.
 * This helps detect issues when logback cannot write to log files.
 */
@Component
public class LogFileMonitor {
    private static final Logger log = LoggerFactory.getLogger(LogFileMonitor.class);
    
    private final LoggerContext loggerContext;
    private boolean lastCheckFailed = false;
    private boolean hasWarnedAboutStaleFile = false; // Track if we've already warned about stale file
    
    public LogFileMonitor() {
        this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        // Register a status listener to capture logback errors
        StatusManager statusManager = loggerContext.getStatusManager();
        statusManager.add(new StatusListener() {
            @Override
            public void addStatusEvent(Status status) {
                if (status.getLevel() == Status.ERROR || status.getLevel() == Status.WARN) {
                    String message = status.getMessage();
                    if (message != null && (
                        message.contains("Failed to create parent directories") ||
                        message.contains("Failed to create file") ||
                        message.contains("Permission denied") ||
                        message.contains("No such file or directory") ||
                        message.contains("FileAppender") ||
                        message.contains("RollingFileAppender")
                    )) {
                        log.error("[LOGFILE ERROR] Logback Status: {} - {}", status.getLevel(), message);
                        if (status.getThrowable() != null) {
                            log.error("[LOGFILE ERROR] Exception details:", status.getThrowable());
                        }
                        lastCheckFailed = true;
                    }
                }
            }
        });
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void checkLogFileAppenderOnStartup() {
        checkLogFileAppenderStatus();
    }
    
    /**
     * Periodically check if log file appender is working correctly
     * Runs every 15 minutes (less frequent to reduce noise)
     */
    @Scheduled(fixedRate = 900000) // 15 minutes
    public void periodicCheck() {
        checkLogFileAppenderStatus();
    }
    
    private void checkLogFileAppenderStatus() {
        try {
            // Check appender status
            ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Appender<?> logFileAppender = rootLogger.getAppender("logFile");
            
            if (logFileAppender == null) {
                log.error("[LOGFILE ERROR] Log file appender 'logFile' not found in root logger!");
                lastCheckFailed = true;
                return;
            }
            
            if (logFileAppender instanceof FileAppender) {
                FileAppender<?> fileAppender = (FileAppender<?>) logFileAppender;
                String fileName = fileAppender.getFile();
                
                if (fileName == null || fileName.isEmpty()) {
                    log.error("[LOGFILE ERROR] Log file appender has no file configured!");
                    lastCheckFailed = true;
                    return;
                }
                
                // Check if file exists or directory is writable
                Path filePath = Paths.get(fileName);
                Path parentDir = filePath.getParent();
                
                if (parentDir != null && Files.exists(parentDir)) {
                    if (!Files.isWritable(parentDir)) {
                        log.error("[LOGFILE ERROR] Log directory is not writable: {}", parentDir.toAbsolutePath());
                        log.error("[LOGFILE ERROR] Please check directory permissions. Log files may not be written.");
                        lastCheckFailed = true;
                    } else {
                        // Check if file exists and is writable
                        if (Files.exists(filePath)) {
                            if (!Files.isWritable(filePath)) {
                                log.error("[LOGFILE ERROR] Log file exists but is not writable: {}", filePath.toAbsolutePath());
                                lastCheckFailed = true;
                            } else {
                                // File exists and is writable - check if it's actually being written to
                                File file = filePath.toFile();
                                long lastModified = file.lastModified();
                                long now = System.currentTimeMillis();
                                long timeSinceLastWrite = now - lastModified;
                                
                                // If file hasn't been modified in last 60 minutes, something might be wrong
                                // Only warn once to avoid log spam during quiet periods
                                if (timeSinceLastWrite > 3600000) { // 60 minutes
                                    if (!hasWarnedAboutStaleFile) {
                                        log.warn("[LOGFILE WARNING] Log file has not been modified in {} minutes: {}", 
                                            timeSinceLastWrite / 60000, filePath.toAbsolutePath());
                                        log.warn("[LOGFILE WARNING] This might indicate logging is not working properly.");
                                        hasWarnedAboutStaleFile = true;
                                    } else {
                                        // Only log at DEBUG level after first warning to reduce noise
                                        log.debug("[LOGFILE WARNING] Log file still not modified ({} minutes): {}", 
                                            timeSinceLastWrite / 60000, filePath.toAbsolutePath());
                                    }
                                } else {
                                    // File is being written to - reset warning flag
                                    if (hasWarnedAboutStaleFile) {
                                        log.info("[LOGFILE OK] Log file is now being written to: {}", filePath.toAbsolutePath());
                                        hasWarnedAboutStaleFile = false;
                                    }
                                    if (lastCheckFailed) {
                                        log.info("[LOGFILE OK] Log file appender is now working correctly: {}", filePath.toAbsolutePath());
                                        lastCheckFailed = false;
                                    }
                                }
                            }
                        } else {
                            // File doesn't exist yet - this is OK if directory is writable
                            log.info("[LOGFILE INFO] Log file will be created at: {}", filePath.toAbsolutePath());
                        }
                    }
                } else {
                    log.error("[LOGFILE ERROR] Log directory does not exist: {}", 
                        parentDir != null ? parentDir.toAbsolutePath() : "null");
                    log.error("[LOGFILE ERROR] Log files cannot be written. Please create the directory.");
                    lastCheckFailed = true;
                }
                
                // Check logback status for errors
                StatusManager statusManager = loggerContext.getStatusManager();
                List<Status> statusList = statusManager.getCopyOfStatusList();
                boolean hasErrors = false;
                
                for (Status status : statusList) {
                    if (status.getLevel() == Status.ERROR) {
                        String message = status.getMessage();
                        if (message != null && message.contains("FileAppender")) {
                            log.error("[LOGFILE ERROR] Logback error detected: {}", message);
                            if (status.getThrowable() != null) {
                                log.error("[LOGFILE ERROR] Exception:", status.getThrowable());
                            }
                            hasErrors = true;
                        }
                    }
                }
                
                if (!hasErrors && !lastCheckFailed) {
                    log.debug("[LOGFILE OK] Log file appender status check passed: {}", filePath.toAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("[LOGFILE ERROR] Exception while checking log file appender status:", e);
            lastCheckFailed = true;
        }
    }
    
    /**
     * Get current status of log file appender
     * Can be called from diagnostic endpoint
     */
    public boolean isLogFileAppenderWorking() {
        return !lastCheckFailed;
    }
}

