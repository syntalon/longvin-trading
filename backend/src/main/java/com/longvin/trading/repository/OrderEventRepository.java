package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for OrderEvent entities (immutable events).
 */
@Repository
public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {
    
    /**
     * Find all events for an order, ordered by event time.
     */
    List<OrderEvent> findByOrderOrderByEventTimeAsc(Order order);
    
    /**
     * Find event by FIX ExecID.
     */
    Optional<OrderEvent> findByFixExecId(String fixExecId);
}

