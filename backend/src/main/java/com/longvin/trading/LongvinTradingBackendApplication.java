package com.longvin.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
public class LongvinTradingBackendApplication {

	static {
		// Set LOG_DIR as system property before logback initializes
		// Logback can access system properties more reliably than environment variables
		String logDir = System.getenv("LOG_DIR");
		if (logDir == null) {
			logDir = System.getProperty("LOG_DIR");
		}
		if (logDir != null && !logDir.isEmpty()) {
			// Set as system property so logback can access it
			System.setProperty("LOG_DIR", logDir);
		}
		
		// Create log directory before logback initializes
		// This runs before Spring Boot starts, ensuring logback can write to files
		ensureLogDirectoryExists();
	}

	public static void main(String[] args) {
		SpringApplication.run(LongvinTradingBackendApplication.class, args);
	}

	/**
	 * Ensures the log directory exists before logback tries to write to it.
	 * This runs in a static block, which executes before Spring Boot initialization.
	 */
	private static void ensureLogDirectoryExists() {
		// Get LOG_DIR from system property (set in static block above) or environment variable
		String logDir = System.getProperty("LOG_DIR");
		if (logDir == null || logDir.isEmpty()) {
			logDir = System.getenv("LOG_DIR");
		}
		if (logDir == null || logDir.isEmpty()) {
			logDir = "logs"; // Default relative path
			System.setProperty("LOG_DIR", logDir); // Set default as system property
		}

		try {
			Path logPath = Paths.get(logDir);
			if (!Files.exists(logPath)) {
				Files.createDirectories(logPath);
				System.out.println("[Logging] Created log directory: " + logPath.toAbsolutePath());
			} else if (!Files.isDirectory(logPath)) {
				System.err.println("[Logging] ERROR: Log path exists but is not a directory: " + logPath.toAbsolutePath());
				return;
			}

			// Check if directory is writable
			if (!Files.isWritable(logPath)) {
				System.err.println("[Logging] WARNING: Log directory exists but is not writable: " + logPath.toAbsolutePath());
				System.err.println("[Logging] Please check directory permissions. Log files may not be written.");
			} else {
				System.out.println("[Logging] Log directory is ready: " + logPath.toAbsolutePath());
			}
		} catch (IOException e) {
			System.err.println("[Logging] ERROR: Failed to create or verify log directory: " + logDir);
			System.err.println("[Logging] Error: " + e.getMessage());
			System.err.println("[Logging] Log files may not be written. Please create the directory manually or check permissions.");
		}
	}
}
