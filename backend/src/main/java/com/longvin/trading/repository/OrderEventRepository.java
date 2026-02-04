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
     * Find all events by FIX ClOrdID (Client Order ID).
     * This is the primary way to link events to orders since ClOrdID is always present.
     * Events can exist independently of orders (event-driven architecture).
     */
    List<OrderEvent> findByFixClOrdIdOrderByEventTimeAsc(String fixClOrdId);
    
    /**
     * Find the latest event by FIX ClOrdID (most recent event time).
     * Returns the most recent event which contains the current order status.
     */
    @Query("SELECT e FROM OrderEvent e WHERE e.fixClOrdId = :fixClOrdId ORDER BY e.eventTime DESC")
    List<OrderEvent> findByFixClOrdIdOrderByEventTimeDesc(String fixClOrdId);
    
    /**
     * Find all events by FIX OrigClOrdID (Original Client Order ID).
     * Used to find events for replace orders where the ClOrdID changed but OrigClOrdID references the original.
     */
    List<OrderEvent> findByFixOrigClOrdIdOrderByEventTimeAsc(String fixOrigClOrdId);
    
    /**
     * Find all events by FIX OrderID (broker-assigned order ID).
     * This is the simplest way to find all events for an order since OrderID remains constant
     * even when ClOrdID changes due to replaces. OrderID is always present in ExecutionReports.
     * 
     * This is particularly useful for shadow account orders where ClOrdID may change multiple times.
     */
    List<OrderEvent> findByFixOrderIdOrderByEventTimeAsc(String fixOrderId);
    
    /**
     * Find all events by FIX OrderID, ordered by event time descending.
     * Used to get the most recent event for an order.
     */
    List<OrderEvent> findByFixOrderIdOrderByEventTimeDesc(String fixOrderId);
    
    /**
     * Find all events that match a ClOrdID or have it as OrigClOrdID.
     * This is used to find all events related to an order, including replace events with temporary ClOrdIDs.
     */
    @Query("SELECT e FROM OrderEvent e WHERE e.fixClOrdId = :clOrdId OR e.fixOrigClOrdId = :clOrdId ORDER BY e.eventTime ASC")
    List<OrderEvent> findByClOrdIdOrOrigClOrdIdOrderByEventTimeAsc(@Param("clOrdId") String clOrdId);
    
    /**
     * Find all events that match a ClOrdID or have it as OrigClOrdID, ordered by event time descending.
     * Used to get the most recent event for an order.
     */
    @Query("SELECT e FROM OrderEvent e WHERE e.fixClOrdId = :clOrdId OR e.fixOrigClOrdId = :clOrdId ORDER BY e.eventTime DESC")
    List<OrderEvent> findByClOrdIdOrOrigClOrdIdOrderByEventTimeDesc(@Param("clOrdId") String clOrdId);
    
    /**
     * Find all events for an order by ClOrdID, OrigClOrdID, or fixOrderId.
     * This handles cases where ExecutionReports use OrderID as ClOrdID instead of the original COPY- prefix.
     * Also handles chained replace events where OrigClOrdID points to a previous temporary ClOrdID.
     * Used to find all events related to an order, including:
     * - Events with matching ClOrdID
     * - Replace events with temporary ClOrdIDs (matching OrigClOrdID or starting with base ClOrdID)
     * - Events where ClOrdID is just the OrderID (matching fixOrderId)
     * - Events where ClOrdID starts with the base ClOrdID (for chained replaces like COPY-XXX-YYY-R...)
     * - Events where OrigClOrdID starts with the base ClOrdID (for chained replaces)
     */
    @Query("SELECT DISTINCT e FROM OrderEvent e WHERE " +
           "e.fixClOrdId = :clOrdId OR e.fixOrigClOrdId = :clOrdId OR " +
           "(:orderId IS NOT NULL AND e.fixOrderId = :orderId) OR " +
           "(e.fixClOrdId LIKE CONCAT(:clOrdId, '-R%')) OR " +
           "(e.fixOrigClOrdId LIKE CONCAT(:clOrdId, '-R%')) " +
           "ORDER BY e.eventTime ASC")
    List<OrderEvent> findByClOrdIdOrOrigClOrdIdOrFixOrderIdOrderByEventTimeAsc(
            @Param("clOrdId") String clOrdId, 
            @Param("orderId") String orderId);
    
    /**
     * Find all events for an order by ClOrdID, OrigClOrdID, or fixOrderId, ordered by event time descending.
     * Used to get the most recent event for an order.
     * Also handles chained replace events where OrigClOrdID points to a previous temporary ClOrdID.
     */
    @Query("SELECT DISTINCT e FROM OrderEvent e WHERE " +
           "e.fixClOrdId = :clOrdId OR e.fixOrigClOrdId = :clOrdId OR " +
           "(:orderId IS NOT NULL AND e.fixOrderId = :orderId) OR " +
           "(e.fixClOrdId LIKE CONCAT(:clOrdId, '-R%')) OR " +
           "(e.fixOrigClOrdId LIKE CONCAT(:clOrdId, '-R%')) " +
           "ORDER BY e.eventTime DESC")
    List<OrderEvent> findByClOrdIdOrOrigClOrdIdOrFixOrderIdOrderByEventTimeDesc(
            @Param("clOrdId") String clOrdId, 
            @Param("orderId") String orderId);
    
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

