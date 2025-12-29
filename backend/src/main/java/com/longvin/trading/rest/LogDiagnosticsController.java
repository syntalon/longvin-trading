package com.longvin.trading.rest;

import com.longvin.trading.config.LogFileMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Diagnostic endpoint to help troubleshoot logging issues
 */
@RestController
@RequestMapping("/api/logs/diagnostics")
public class LogDiagnosticsController {
    private static final Logger log = LoggerFactory.getLogger(LogDiagnosticsController.class);
    
    private final LogFileMonitor logFileMonitor;
    
    public LogDiagnosticsController(LogFileMonitor logFileMonitor) {
        this.logFileMonitor = logFileMonitor;
    }
    
    @GetMapping
    public ResponseEntity<Map<String, Object>> getLogDiagnostics() {
        Map<String, Object> diagnostics = new HashMap<>();
        
        // Get LOG_DIR from environment or system property
        String logDir = System.getenv("LOG_DIR");
        if (logDir == null) {
            logDir = System.getProperty("LOG_DIR");
        }
        if (logDir == null) {
            logDir = "logs"; // Default
        }
        
        diagnostics.put("logDir", logDir);
        diagnostics.put("logDirFromEnv", System.getenv("LOG_DIR"));
        diagnostics.put("logDirFromProperty", System.getProperty("LOG_DIR"));
        
        Path logPath = Paths.get(logDir);
        diagnostics.put("logPathAbsolute", logPath.toAbsolutePath().toString());
        diagnostics.put("logPathExists", Files.exists(logPath));
        diagnostics.put("logPathIsDirectory", Files.exists(logPath) && Files.isDirectory(logPath));
        
        if (Files.exists(logPath)) {
            try {
                diagnostics.put("logPathWritable", Files.isWritable(logPath));
                diagnostics.put("logPathReadable", Files.isReadable(logPath));
            } catch (Exception e) {
                diagnostics.put("logPathWritable", false);
                diagnostics.put("logPathReadable", false);
                diagnostics.put("permissionCheckError", e.getMessage());
            }
        } else {
            diagnostics.put("logPathWritable", false);
            diagnostics.put("logPathReadable", false);
        }
        
        // Check for log files
        if (Files.exists(logPath) && Files.isDirectory(logPath)) {
            try {
                File[] logFiles = logPath.toFile().listFiles((dir, name) -> 
                    name.endsWith(".log") && name.startsWith("longvinTrading-"));
                
                if (logFiles != null) {
                    diagnostics.put("logFilesFound", logFiles.length);
                    String[] fileNames = new String[logFiles.length];
                    long[] fileSizes = new long[logFiles.length];
                    for (int i = 0; i < logFiles.length; i++) {
                        fileNames[i] = logFiles[i].getName();
                        fileSizes[i] = logFiles[i].length();
                    }
                    diagnostics.put("logFileNames", fileNames);
                    diagnostics.put("logFileSizes", fileSizes);
                } else {
                    diagnostics.put("logFilesFound", 0);
                }
            } catch (Exception e) {
                diagnostics.put("logFilesCheckError", e.getMessage());
            }
        }
        
        // Check working directory
        diagnostics.put("workingDirectory", System.getProperty("user.dir"));
        
        // Test write capability
        try {
            Path testFile = logPath.resolve(".write-test-" + System.currentTimeMillis());
            Files.createFile(testFile);
            Files.delete(testFile);
            diagnostics.put("writeTest", "SUCCESS");
        } catch (IOException e) {
            diagnostics.put("writeTest", "FAILED");
            diagnostics.put("writeTestError", e.getMessage());
        }
        
        // Log a test message
        log.info("Log diagnostics endpoint called - this message should appear in log files if logging is working");
        diagnostics.put("testLogMessage", "A test log message was written. Check log files for: 'Log diagnostics endpoint called'");
        
        // Check log file monitor status
        diagnostics.put("logFileAppenderWorking", logFileMonitor.isLogFileAppenderWorking());
        
        // Check if test log message appears in file
        if (Files.exists(logPath) && Files.isDirectory(logPath)) {
            try {
                File[] logFiles = logPath.toFile().listFiles((dir, name) -> 
                    name.endsWith(".log") && name.startsWith("longvinTrading-"));
                
                if (logFiles != null && logFiles.length > 0) {
                    // Check most recent log file for test message
                    File mostRecent = logFiles[0];
                    for (File f : logFiles) {
                        if (f.lastModified() > mostRecent.lastModified()) {
                            mostRecent = f;
                        }
                    }
                    
                    String content = new String(Files.readAllBytes(mostRecent.toPath()));
                    boolean testMessageFound = content.contains("Log diagnostics endpoint called");
                    diagnostics.put("testMessageInFile", testMessageFound);
                    diagnostics.put("testMessageCheckedFile", mostRecent.getName());
                }
            } catch (Exception e) {
                diagnostics.put("testMessageCheckError", e.getMessage());
            }
        }
        
        return ResponseEntity.ok(diagnostics);
    }
}

