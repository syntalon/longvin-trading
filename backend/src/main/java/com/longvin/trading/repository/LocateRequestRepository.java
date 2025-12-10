package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.entities.orders.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for LocateRequest entities.
 */
@Repository
public interface LocateRequestRepository extends JpaRepository<LocateRequest, UUID> {
    
    /**
     * Find locate request by FIX QuoteReqID (tag 131).
     */
    Optional<LocateRequest> findByFixQuoteReqId(String fixQuoteReqId);
    
    /**
     * Find locate requests for an order, ordered by creation time descending.
     */
    List<LocateRequest> findByOrderOrderByCreatedAtDesc(Order order);
    
    /**
     * Find locate requests for an order with specific status.
     */
    List<LocateRequest> findByOrderAndStatus(Order order, LocateRequest.LocateStatus status);
    
    /**
     * Find all locate requests with a specific status.
     */
    List<LocateRequest> findByStatus(LocateRequest.LocateStatus status);
    
    /**
     * Find pending locate requests created before a specific time.
     * Useful for finding expired locate requests.
     */
    @Query("SELECT lr FROM LocateRequest lr WHERE lr.status = :status AND lr.createdAt < :before")
    List<LocateRequest> findByStatusAndCreatedAtBefore(
        @Param("status") LocateRequest.LocateStatus status,
        @Param("before") LocalDateTime before
    );
    
    /**
     * Find locate requests for a specific account.
     */
    List<LocateRequest> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    
    /**
     * Find locate requests for a specific account with a specific status.
     */
    List<LocateRequest> findByAccountIdAndStatus(Long accountId, LocateRequest.LocateStatus status);
    
    /**
     * Find locate requests by symbol.
     */
    List<LocateRequest> findBySymbolOrderByCreatedAtDesc(String symbol);
    
    /**
     * Find locate requests by symbol and status.
     */
    List<LocateRequest> findBySymbolAndStatus(String symbol, LocateRequest.LocateStatus status);
}

