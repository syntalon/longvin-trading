package com.longvin.trading.dto.copy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * DTO for CopyRule entity.
 * Used for API responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopyRuleDto {
    
    private Long id;
    private Long primaryAccountId;
    private String primaryAccountNumber;
    private Long shadowAccountId;
    private String shadowAccountNumber;
    private String ratioType; // PERCENTAGE, MULTIPLIER, FIXED_QUANTITY
    private BigDecimal ratioValue;
    private String orderTypes; // Comma-separated list
    private String copyRoute;
    private String locateRoute;
    private String copyBroker;
    private BigDecimal minQuantity;
    private BigDecimal maxQuantity;
    private Integer priority;
    private Boolean active;
    private String description;
    private Map<String, Object> config;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

