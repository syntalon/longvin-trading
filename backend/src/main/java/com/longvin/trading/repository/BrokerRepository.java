package com.longvin.trading.repository;

import com.longvin.trading.entities.accounts.Broker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Broker entities.
 */
@Repository
public interface BrokerRepository extends JpaRepository<Broker, Long> {
    
    /**
     * Find broker by name.
     */
    Optional<Broker> findByName(String name);
    
    /**
     * Find broker by code.
     */
    Optional<Broker> findByCode(String code);
    
    /**
     * Find all active brokers.
     */
    List<Broker> findByActiveTrue();
    
    /**
     * Find all brokers (active and inactive).
     */
    List<Broker> findAll();
    
    /**
     * Check if broker exists by name.
     */
    boolean existsByName(String name);
    
    /**
     * Check if broker exists by code.
     */
    boolean existsByCode(String code);
}

