package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Order entities.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    
    /**
     * Find order by FIX OrderID.
     */
    Optional<Order> findByFixOrderId(String fixOrderId);
    
    /**
     * Find order by FIX ClOrdID.
     */
    Optional<Order> findByFixClOrdId(String fixClOrdId);
}

