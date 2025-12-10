package com.longvin.trading.repository;

import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for OrderEvent entities (immutable events).
 */
@Repository
public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {
    
    /**
     * Find all events for an order, ordered by event time ascending.
     */
    List<OrderEvent> findByOrderOrderByEventTimeAsc(Order order);
    
    /**
     * Find all events for an order, ordered by event time descending.
     */
    List<OrderEvent> findByOrderOrderByEventTimeDesc(Order order);
    
    /**
     * Find all events for an order by order ID.
     */
    List<OrderEvent> findByOrderIdOrderByEventTimeAsc(UUID orderId);
    
    /**
     * Find event by FIX ExecID.
     */
    Optional<OrderEvent> findByFixExecId(String fixExecId);
    
    /**
     * Find events by execution type.
     */
    @Query("SELECT e FROM OrderEvent e WHERE e.execType = :execType ORDER BY e.eventTime DESC")
    List<OrderEvent> findByExecType(@Param("execType") Character execType);
    
    /**
     * Find events by order status.
     */
    @Query("SELECT e FROM OrderEvent e WHERE e.ordStatus = :ordStatus ORDER BY e.eventTime DESC")
    List<OrderEvent> findByOrdStatus(@Param("ordStatus") Character ordStatus);
    
    /**
     * Find events by symbol.
     */
    List<OrderEvent> findBySymbolOrderByEventTimeDesc(String symbol);
    
    /**
     * Find events created after a specific time.
     */
    List<OrderEvent> findByEventTimeAfter(LocalDateTime after);
    
    /**
     * Find events created between two times.
     */
    List<OrderEvent> findByEventTimeBetween(LocalDateTime start, LocalDateTime end);
    
    /**
     * Find events for a specific session ID.
     */
    List<OrderEvent> findBySessionIdOrderByEventTimeDesc(String sessionId);
    
    /**
     * Count events for an order.
     */
    long countByOrder(Order order);
    
    /**
     * Count events for an order by order ID.
     */
    long countByOrderId(UUID orderId);
}

