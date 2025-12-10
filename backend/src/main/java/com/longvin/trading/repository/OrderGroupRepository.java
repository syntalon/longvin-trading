package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.OrderGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for OrderGroup entities.
 */
@Repository
public interface OrderGroupRepository extends JpaRepository<OrderGroup, UUID> {
    
    /**
     * Find order group by strategy key.
     */
    Optional<OrderGroup> findByStrategyKey(String strategyKey);
    
    /**
     * Find order groups created after a specific time.
     */
    List<OrderGroup> findByCreatedAtAfter(LocalDateTime after);
    
    /**
     * Find order groups created between two times.
     */
    List<OrderGroup> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    
    /**
     * Find order group by primary order ID.
     */
    @Query("SELECT og FROM OrderGroup og WHERE og.primaryOrder.id = :orderId")
    Optional<OrderGroup> findByPrimaryOrderId(@Param("orderId") UUID orderId);
    
    /**
     * Find all order groups ordered by creation time descending.
     */
    List<OrderGroup> findAllByOrderByCreatedAtDesc();
}

