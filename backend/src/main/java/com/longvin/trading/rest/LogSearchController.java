package com.longvin.trading.rest;

import com.longvin.trading.service.LogSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/logs")
public class LogSearchController {
    
    private final LogSearchService logSearchService;
    
    public LogSearchController(LogSearchService logSearchService) {
        this.logSearchService = logSearchService;
    }
    
    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> getAvailableLogFiles() {
        List<String> files = logSearchService.getAvailableLogFiles();
        Map<String, Object> response = new HashMap<>();
        response.put("files", files);
        response.put("count", files.size());
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchLogs(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String file,
            @RequestParam(defaultValue = "1000") int maxLines,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        
        LogSearchService.LogSearchResult result = logSearchService.searchLogs(
            query, file, maxLines, fromDate, toDate);
        
        Map<String, Object> response = new HashMap<>();
        
        if (result.getError() != null) {
            response.put("error", result.getError());
            response.put("entries", List.of());
            return ResponseEntity.ok(response);
        }
        
        List<Map<String, Object>> entries = result.getEntries().stream()
            .map(entry -> {
                Map<String, Object> map = new HashMap<>();
                map.put("fileName", entry.getFileName());
                map.put("lineNumber", entry.getLineNumber());
                map.put("content", entry.getContent());
                return map;
            })
            .collect(Collectors.toList());
        
        response.put("entries", entries);
        response.put("count", entries.size());
        response.put("query", query != null ? query : "");
        response.put("file", file != null ? file : "all");
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentLogs(
            @RequestParam(required = false) String file,
            @RequestParam(defaultValue = "100") int maxLines) {
        
        LogSearchService.LogSearchResult result = logSearchService.getRecentLogs(file, maxLines);
        
        Map<String, Object> response = new HashMap<>();
        
        if (result.getError() != null) {
            response.put("error", result.getError());
            response.put("entries", List.of());
            return ResponseEntity.ok(response);
        }
        
        List<Map<String, Object>> entries = result.getEntries().stream()
            .map(entry -> {
                Map<String, Object> map = new HashMap<>();
                map.put("fileName", entry.getFileName());
                map.put("lineNumber", entry.getLineNumber());
                map.put("content", entry.getContent());
                return map;
            })
            .collect(Collectors.toList());
        
        response.put("entries", entries);
        response.put("count", entries.size());
        response.put("file", file != null ? file : "most recent");
        
        return ResponseEntity.ok(response);
    }
}

