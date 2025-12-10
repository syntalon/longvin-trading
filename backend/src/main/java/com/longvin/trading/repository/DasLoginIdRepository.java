package com.longvin.trading.repository;

import com.longvin.trading.entities.accounts.DasLoginId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for DasLoginId entities.
 */
@Repository
public interface DasLoginIdRepository extends JpaRepository<DasLoginId, Long> {
    
    /**
     * Find DAS login ID by login ID string.
     */
    Optional<DasLoginId> findByLoginId(String loginId);
    
    /**
     * Find all active DAS login IDs.
     */
    List<DasLoginId> findByActiveTrue();
    
    /**
     * Find all DAS login IDs (active and inactive).
     */
    List<DasLoginId> findAll();
    
    /**
     * Check if DAS login ID exists by login ID string.
     */
    boolean existsByLoginId(String loginId);
}

