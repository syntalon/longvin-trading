package com.longvin.trading.dto.copy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Request DTO for creating a new CopyRule.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCopyRuleRequest {
    
    private String primaryAccountNumber;
    private String shadowAccountNumber;
    private String ratioType; // PERCENTAGE, MULTIPLIER, FIXED_QUANTITY
    private BigDecimal ratioValue;
    
    private String orderTypes; // Comma-separated list of OrdType values
    private String copyRoute;
    private String locateRoute;
    private String copyBroker;
    private BigDecimal minQuantity;
    private BigDecimal maxQuantity;
    private Integer priority;
    private Boolean active;
    private String description;
    private Map<String, Object> config;
}

