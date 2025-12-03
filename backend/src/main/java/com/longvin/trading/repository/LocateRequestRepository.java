package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.entities.orders.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for LocateRequest entities.
 */
@Repository
public interface LocateRequestRepository extends JpaRepository<LocateRequest, UUID> {
    
    /**
     * Find locate request by FIX LocateReqID.
     */
    Optional<LocateRequest> findByFixLocateReqId(String fixLocateReqId);
    
    /**
     * Find locate requests for an order.
     */
    List<LocateRequest> findByOrderOrderByCreatedAtDesc(Order order);
    
    /**
     * Find pending locate requests for an order.
     */
    List<LocateRequest> findByOrderAndStatus(Order order, LocateRequest.LocateStatus status);
}

