package com.longvin.trading.service;

import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.repository.LocateRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for monitoring locate requests and handling timeouts.
 * Periodically checks for pending locate requests that have exceeded their timeout period.
 */
@Service
public class LocateRequestMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(LocateRequestMonitoringService.class);

    /**
     * Timeout for locate requests (default: 30 seconds).
     * Locate requests that remain PENDING beyond this duration will be marked as EXPIRED.
     */
    private static final Duration LOCATE_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final LocateRequestRepository locateRequestRepository;

    public LocateRequestMonitoringService(LocateRequestRepository locateRequestRepository) {
        this.locateRequestRepository = locateRequestRepository;
    }

    /**
     * Check for expired locate requests and mark them as EXPIRED.
     * Runs every 10 seconds.
     */
    @Scheduled(fixedRate = 10000) // Every 10 seconds
    @Transactional
    public void checkExpiredLocateRequests() {
        try {
            LocalDateTime expirationThreshold = LocalDateTime.now().minus(LOCATE_REQUEST_TIMEOUT);
            List<LocateRequest> expiredRequests = locateRequestRepository.findByStatusAndCreatedAtBefore(
                LocateRequest.LocateStatus.PENDING,
                expirationThreshold
            );

            if (!expiredRequests.isEmpty()) {
                log.warn("Found {} expired locate requests", expiredRequests.size());
                
                for (LocateRequest request : expiredRequests) {
                    markAsExpired(request);
                }
            }
        } catch (Exception e) {
            log.error("Error checking expired locate requests", e);
        }
    }


    /**
     * Mark a locate request as expired.
     */
    private void markAsExpired(LocateRequest request) {
        log.warn("Marking locate request as EXPIRED: QuoteReqID={}, Symbol={}, Qty={}, Age={}s", 
            request.getFixQuoteReqId(), 
            request.getSymbol(), 
            request.getQuantity(),
            Duration.between(request.getCreatedAt(), LocalDateTime.now()).getSeconds());
        
        request.setStatus(LocateRequest.LocateStatus.EXPIRED);
        request.setResponseMessage("Locate request timed out after " + LOCATE_REQUEST_TIMEOUT.getSeconds() + " seconds");
        locateRequestRepository.save(request);
    }

    /**
     * Get the timeout duration for locate requests.
     */
    public Duration getLocateRequestTimeout() {
        return LOCATE_REQUEST_TIMEOUT;
    }
}

