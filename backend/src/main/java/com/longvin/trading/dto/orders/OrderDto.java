package com.longvin.trading.dto.orders;

import com.longvin.trading.entities.accounts.AccountType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for Order entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto {
    private UUID id;
    private Long accountId;
    private String accountNumber;
    private AccountType accountType;
    private String primaryOrderClOrdId;
    private String fixOrderId;
    private String fixClOrdId;
    private String fixOrigClOrdId;
    private String symbol;
    private Character side;
    private Character ordType;
    private Character timeInForce;
    private BigDecimal orderQty;
    private BigDecimal price;
    private BigDecimal stopPx;
    private String exDestination;
    private Character execType;
    private Character ordStatus;
    private BigDecimal cumQty;
    private BigDecimal leavesQty;
    private BigDecimal avgPx;
    private BigDecimal lastPx;
    private BigDecimal lastQty;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Helper fields
    private Boolean isCopyOrder; // true if ClOrdID starts with "COPY-"
    private Integer eventCount; // Number of OrderEvents for this order
}

