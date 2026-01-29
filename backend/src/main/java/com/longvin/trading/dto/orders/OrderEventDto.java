package com.longvin.trading.dto.orders;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for OrderEvent entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEventDto {
    private UUID id;
    private UUID orderId; // Can be null for event-driven architecture
    private String fixExecId;
    private Character execType;
    private Character ordStatus;
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
    private BigDecimal lastPx;
    private BigDecimal lastQty;
    private BigDecimal cumQty;
    private BigDecimal leavesQty;
    private BigDecimal avgPx;
    private String account;
    private LocalDateTime transactTime;
    private String text;
    private LocalDateTime eventTime;
    private String rawFixMessage;
    private String sessionId;
}

