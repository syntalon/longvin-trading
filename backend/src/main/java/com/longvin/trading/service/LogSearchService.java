package com.longvin.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class LogSearchService {
    private static final Logger log = LoggerFactory.getLogger(LogSearchService.class);
    
    // Use environment variable LOG_DIR or default to "logs" relative to working directory
    // This should match the LOG_DIR used in logback.xml
    private static final String LOG_DIR = System.getenv("LOG_DIR") != null 
        ? System.getenv("LOG_DIR") 
        : (System.getProperty("LOG_DIR") != null ? System.getProperty("LOG_DIR") : "logs");
    private static final String LOG_FILE_PREFIX = "longvinTrading";
    private static final String LOG_FILE_PATTERN = "longvinTrading-"; // For daily rolled files
    private static final String LOG_FILE_CURRENT = "longvinTrading.log"; // Current active file
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    /**
     * Get list of available log files
     */
    public List<String> getAvailableLogFiles() {
        List<String> logFiles = new ArrayList<>();
        try {
            Path logDir = Paths.get(LOG_DIR);
            if (Files.exists(logDir) && Files.isDirectory(logDir)) {
                try (Stream<Path> paths = Files.list(logDir)) {
                    logFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            // Include both current log file and daily rolled files
                            return fileName.equals(LOG_FILE_CURRENT) || 
                                   fileName.startsWith(LOG_FILE_PREFIX) && fileName.endsWith(".log");
                        })
                        .map(path -> path.getFileName().toString())
                        .sorted((a, b) -> {
                            // Sort: current file first, then daily files by name (most recent first)
                            if (a.equals(LOG_FILE_CURRENT)) return -1;
                            if (b.equals(LOG_FILE_CURRENT)) return 1;
                            return b.compareTo(a); // Reverse order for daily files
                        })
                        .collect(Collectors.toList());
                }
            }
        } catch (IOException e) {
            log.error("Error listing log files", e);
        }
        return logFiles;
    }
    
    /**
     * Search logs in a specific file or all files
     * @param searchText The text to search for (case-insensitive)
     * @param logFileName Specific log file name, or null to search all files
     * @param maxLines Maximum number of lines to return
     * @param fromDate Optional start date (yyyy-MM-dd format)
     * @param toDate Optional end date (yyyy-MM-dd format)
     */
    public LogSearchResult searchLogs(String searchText, String logFileName, int maxLines, String fromDate, String toDate) {
        List<LogEntry> results = new ArrayList<>();
        
        try {
            Path logDir = Paths.get(LOG_DIR);
            if (!Files.exists(logDir) || !Files.isDirectory(logDir)) {
                return new LogSearchResult(results, "Log directory not found: " + LOG_DIR);
            }
            
            List<Path> filesToSearch = new ArrayList<>();
            
            if (logFileName != null && !logFileName.isEmpty()) {
                // Search specific file
                Path filePath = logDir.resolve(logFileName);
                if (Files.exists(filePath)) {
                    filesToSearch.add(filePath);
                } else {
                    return new LogSearchResult(results, "Log file not found: " + logFileName);
                }
            } else {
                // Search all log files
                try (Stream<Path> paths = Files.list(logDir)) {
                    filesToSearch = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            // Include both current log file and daily rolled files
                            return fileName.equals(LOG_FILE_CURRENT) || 
                                   fileName.startsWith(LOG_FILE_PREFIX) && fileName.endsWith(".log");
                        })
                        .sorted((a, b) -> {
                            // Sort: current file first, then daily files by name (most recent first)
                            String aName = a.getFileName().toString();
                            String bName = b.getFileName().toString();
                            if (aName.equals(LOG_FILE_CURRENT)) return -1;
                            if (bName.equals(LOG_FILE_CURRENT)) return 1;
                            return bName.compareTo(aName); // Reverse order for daily files
                        })
                        .collect(Collectors.toList());
                }
            }
            
            // Filter by date range if provided
            if (fromDate != null || toDate != null) {
                LocalDate from = fromDate != null ? LocalDate.parse(fromDate) : null;
                LocalDate to = toDate != null ? LocalDate.parse(toDate) : null;
                
                filesToSearch = filesToSearch.stream()
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        // Always include current log file (longvinTrading.log)
                        if (fileName.equals(LOG_FILE_CURRENT)) {
                            return true;
                        }
                        // For daily rolled files, check date
                        if (fileName.startsWith(LOG_FILE_PATTERN)) {
                            String dateStr = fileName.substring(LOG_FILE_PATTERN.length(), 
                                fileName.lastIndexOf('.'));
                            try {
                                LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                                if (from != null && fileDate.isBefore(from)) {
                                    return false;
                                }
                                if (to != null && fileDate.isAfter(to)) {
                                    return false;
                                }
                                return true;
                            } catch (Exception e) {
                                return true; // Include if date parsing fails
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            }
            
            // Search in files
            String lowerSearchText = searchText != null ? searchText.toLowerCase() : "";
            
            for (Path filePath : filesToSearch) {
                if (results.size() >= maxLines) {
                    break;
                }
                
                try {
                    List<String> lines = Files.readAllLines(filePath);
                    String fileName = filePath.getFileName().toString();
                    
                    for (int i = 0; i < lines.size() && results.size() < maxLines; i++) {
                        String line = lines.get(i);
                        if (searchText == null || searchText.isEmpty() || 
                            line.toLowerCase().contains(lowerSearchText)) {
                            results.add(new LogEntry(fileName, i + 1, line));
                        }
                    }
                } catch (IOException e) {
                    log.error("Error reading log file: " + filePath, e);
                }
            }
            
            // Sort by file name (most recent first) and line number (newest lines first)
            results.sort((a, b) -> {
                int fileCompare = b.getFileName().compareTo(a.getFileName());
                if (fileCompare != 0) {
                    return fileCompare;
                }
                // Reverse line number order - newest lines first
                return Integer.compare(b.getLineNumber(), a.getLineNumber());
            });
            
        } catch (IOException e) {
            log.error("Error searching logs", e);
            return new LogSearchResult(results, "Error searching logs: " + e.getMessage());
        }
        
        return new LogSearchResult(results, null);
    }
    
    /**
     * Get recent log entries (tail-like functionality)
     * @param logFileName Specific log file name, or null for most recent file
     * @param maxLines Maximum number of lines to return
     */
    public LogSearchResult getRecentLogs(String logFileName, int maxLines) {
        try {
            Path logDir = Paths.get(LOG_DIR);
            if (!Files.exists(logDir) || !Files.isDirectory(logDir)) {
                return new LogSearchResult(new ArrayList<>(), "Log directory not found: " + LOG_DIR);
            }
            
            Path fileToRead;
            
            if (logFileName != null && !logFileName.isEmpty()) {
                fileToRead = logDir.resolve(logFileName);
                if (!Files.exists(fileToRead)) {
                    return new LogSearchResult(new ArrayList<>(), "Log file not found: " + logFileName);
                }
            } else {
                // Get most recent log file - prefer current file, then most recent daily file
                try (Stream<Path> paths = Files.list(logDir)) {
                    Path currentFile = null;
                    Path mostRecentDaily = null;
                    
                    for (Path path : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
                        String fileName = path.getFileName().toString();
                        if (fileName.equals(LOG_FILE_CURRENT)) {
                            currentFile = path;
                        } else if (fileName.startsWith(LOG_FILE_PREFIX) && fileName.endsWith(".log")) {
                            if (mostRecentDaily == null || fileName.compareTo(mostRecentDaily.getFileName().toString()) > 0) {
                                mostRecentDaily = path;
                            }
                        }
                    }
                    
                    // Prefer current file, fall back to most recent daily file
                    fileToRead = currentFile != null ? currentFile : mostRecentDaily;
                    
                    if (fileToRead == null) {
                        return new LogSearchResult(new ArrayList<>(), "No log files found");
                    }
                }
            }
            
            List<String> allLines = Files.readAllLines(fileToRead);
            String fileName = fileToRead.getFileName().toString();
            
            // Get last maxLines and reverse order (newest first)
            int startIndex = Math.max(0, allLines.size() - maxLines);
            List<LogEntry> results = new ArrayList<>();
            
            // Add entries in reverse order (newest first)
            for (int i = allLines.size() - 1; i >= startIndex; i--) {
                results.add(new LogEntry(fileName, i + 1, allLines.get(i)));
            }
            
            return new LogSearchResult(results, null);
            
        } catch (IOException e) {
            log.error("Error reading recent logs", e);
            return new LogSearchResult(new ArrayList<>(), "Error reading logs: " + e.getMessage());
        }
    }
    
    // Inner classes for response
    public static class LogSearchResult {
        private final List<LogEntry> entries;
        private final String error;
        
        public LogSearchResult(List<LogEntry> entries, String error) {
            this.entries = entries;
            this.error = error;
        }
        
        public List<LogEntry> getEntries() {
            return entries;
        }
        
        public String getError() {
            return error;
        }
    }
    
    public static class LogEntry {
        private final String fileName;
        private final int lineNumber;
        private final String content;
        
        public LogEntry(String fileName, int lineNumber, String content) {
            this.fileName = fileName;
            this.lineNumber = lineNumber;
            this.content = content;
        }
        
        public String getFileName() {
            return fileName;
        }
        
        public int getLineNumber() {
            return lineNumber;
        }
        
        public String getContent() {
            return content;
        }
    }
}

