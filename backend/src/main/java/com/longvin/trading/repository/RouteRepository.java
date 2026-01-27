package com.longvin.trading.repository;

import com.longvin.trading.entities.accounts.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Route entities.
 */
@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {
    
    /**
     * Find route by broker ID and route name.
     */
    Optional<Route> findByBrokerIdAndNameIgnoreCase(Long brokerId, String name);
    
    /**
     * Find route by broker name and route name.
     */
    @Query("SELECT r FROM Route r WHERE r.broker.name = :brokerName AND UPPER(r.name) = UPPER(:routeName)")
    Optional<Route> findByBrokerNameAndRouteName(@Param("brokerName") String brokerName, @Param("routeName") String routeName);
    
    /**
     * Find all active routes for a broker.
     */
    @Query("SELECT r FROM Route r WHERE r.broker.id = :brokerId AND r.active = true ORDER BY r.priority ASC, r.name ASC")
    List<Route> findActiveRoutesByBrokerId(@Param("brokerId") Long brokerId);
    
    /**
     * Find all active routes for a broker by broker name.
     */
    @Query("SELECT r FROM Route r WHERE r.broker.name = :brokerName AND r.active = true ORDER BY r.priority ASC, r.name ASC")
    List<Route> findActiveRoutesByBrokerName(@Param("brokerName") String brokerName);
    
    /**
     * Find route by name across all brokers.
     */
    @Query("SELECT r FROM Route r WHERE UPPER(r.name) = UPPER(:routeName) AND r.active = true")
    List<Route> findActiveRoutesByName(@Param("routeName") String routeName);
    
    /**
     * Find all active routes.
     */
    List<Route> findByActiveTrue();
    
    /**
     * Check if route exists for a broker.
     */
    boolean existsByBrokerIdAndNameIgnoreCase(Long brokerId, String name);
}

