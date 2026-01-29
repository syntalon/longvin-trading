package com.longvin.trading.repository;

import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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
    
    /**
     * Find order by FIX OrigClOrdID (for replaced/canceled orders).
     */
    Optional<Order> findByFixOrigClOrdId(String fixOrigClOrdId);
    
    /**
     * Find all orders for a specific account.
     */
    List<Order> findByAccountOrderByCreatedAtDesc(Account account);
    
    /**
     * Find all orders for a specific account with pagination.
     */
    Page<Order> findByAccountOrderByCreatedAtDesc(Account account, Pageable pageable);
    
    /**
     * Find all orders for a specific account ID.
     */
    List<Order> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    
    /**
     * Find all orders in an order group.
     */
    List<Order> findByOrderGroupOrderByCreatedAtAsc(OrderGroup orderGroup);
    
    /**
     * Find all orders in an order group by ID.
     */
    List<Order> findByOrderGroupIdOrderByCreatedAtAsc(UUID orderGroupId);
    
    /**
     * Find orders by symbol.
     */
    List<Order> findBySymbolOrderByCreatedAtDesc(String symbol);
    
    /**
     * Find orders by symbol with pagination.
     */
    Page<Order> findBySymbolOrderByCreatedAtDesc(String symbol, Pageable pageable);
    
    /**
     * Find orders by symbol and account.
     */
    List<Order> findBySymbolAndAccountOrderByCreatedAtDesc(String symbol, Account account);
    
    /**
     * Find orders by symbol and account with pagination.
     */
    Page<Order> findBySymbolAndAccountOrderByCreatedAtDesc(String symbol, Account account, Pageable pageable);
    
    /**
     * Find orders created after a specific time.
     */
    List<Order> findByCreatedAtAfter(LocalDateTime after);
    
    /**
     * Find orders created between two times.
     */
    List<Order> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    
    /**
     * Find orders created between two times with pagination.
     */
    Page<Order> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);
    
    /**
     * Find orders by order status (ordStatus field).
     */
    @Query("SELECT o FROM Order o WHERE o.ordStatus = :ordStatus ORDER BY o.createdAt DESC")
    List<Order> findByOrdStatus(@Param("ordStatus") Character ordStatus);
    
    /**
     * Find orders by order status with pagination.
     */
    @Query("SELECT o FROM Order o WHERE o.ordStatus = :ordStatus ORDER BY o.createdAt DESC")
    Page<Order> findByOrdStatus(@Param("ordStatus") Character ordStatus, Pageable pageable);
    
    /**
     * Find orders by execution type (execType field).
     */
    @Query("SELECT o FROM Order o WHERE o.execType = :execType ORDER BY o.createdAt DESC")
    List<Order> findByExecType(@Param("execType") Character execType);
    
    /**
     * Find orders by execution type with pagination.
     */
    @Query("SELECT o FROM Order o WHERE o.execType = :execType ORDER BY o.createdAt DESC")
    Page<Order> findByExecType(@Param("execType") Character execType, Pageable pageable);
}

