package com.longvin.trading.repository;

import com.longvin.trading.entities.copy.CopyRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for CopyRule entities.
 */
@Repository
public interface CopyRuleRepository extends JpaRepository<CopyRule, Long> {
    
    /**
     * Find all active copy rules for a primary account.
     */
    @Query("SELECT r FROM CopyRule r WHERE r.primaryAccount.id = :primaryAccountId AND r.active = true ORDER BY r.priority ASC")
    List<CopyRule> findActiveRulesByPrimaryAccountId(@Param("primaryAccountId") Long primaryAccountId);
    
    /**
     * Find all active copy rules for a shadow account.
     */
    @Query("SELECT r FROM CopyRule r WHERE r.shadowAccount.id = :shadowAccountId AND r.active = true ORDER BY r.priority ASC")
    List<CopyRule> findActiveRulesByShadowAccountId(@Param("shadowAccountId") Long shadowAccountId);
    
    /**
     * Find copy rule for a specific primary->shadow account pair.
     */
    @Query("SELECT r FROM CopyRule r WHERE r.primaryAccount.id = :primaryAccountId AND r.shadowAccount.id = :shadowAccountId AND r.active = true")
    Optional<CopyRule> findActiveRuleByAccountPair(@Param("primaryAccountId") Long primaryAccountId, 
                                                    @Param("shadowAccountId") Long shadowAccountId);
    
    /**
     * Find all active copy rules.
     * Eagerly fetches primary and shadow accounts to avoid lazy loading issues.
     */
    @Query("SELECT DISTINCT r FROM CopyRule r " +
           "LEFT JOIN FETCH r.primaryAccount " +
           "LEFT JOIN FETCH r.shadowAccount " +
           "WHERE r.active = true")
    List<CopyRule> findByActiveTrue();
    
    /**
     * Check if copy rule exists for account pair.
     */
    @Query("SELECT COUNT(r) > 0 FROM CopyRule r WHERE r.primaryAccount.id = :primaryAccountId AND r.shadowAccount.id = :shadowAccountId")
    boolean existsByAccountPair(@Param("primaryAccountId") Long primaryAccountId, 
                                @Param("shadowAccountId") Long shadowAccountId);
}

