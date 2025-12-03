package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.OrderGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

