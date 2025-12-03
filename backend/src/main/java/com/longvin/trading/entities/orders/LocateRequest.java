package com.longvin.trading.entities.orders;

import com.longvin.trading.entities.accounts.Account;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity for tracking locate requests for short orders.
 * When a short order is detected, a locate request is sent to check stock availability for borrowing.
 */
@Entity
@Table(name = "locate_requests", indexes = {
    @Index(name = "idx_locate_request_order_id", columnList = "order_id"),
    @Index(name = "idx_locate_request_account_id", columnList = "account_id"),
    @Index(name = "idx_locate_request_symbol", columnList = "symbol"),
    @Index(name = "idx_locate_request_status", columnList = "status"),
    @Index(name = "idx_locate_request_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LocateRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private UUID id;

    /**
     * The order that triggered this locate request.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * The account requesting the locate.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * Symbol to locate/borrow.
     */
    @Column(nullable = false, length = 50)
    private String symbol;

    /**
     * Quantity requested.
     */
    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;

    /**
     * Status of the locate request.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LocateStatus status = LocateStatus.PENDING;

    /**
     * Quantity available for borrowing (from locate response).
     */
    @Column(name = "available_qty", precision = 18, scale = 8)
    private BigDecimal availableQty;

    /**
     * Quantity actually borrowed.
     */
    @Column(name = "borrowed_qty", precision = 18, scale = 8)
    private BigDecimal borrowedQty;

    /**
     * Locate ID from the broker/exchange response.
     */
    @Column(name = "locate_id", length = 100)
    private String locateId;

    /**
     * Response message or reason if locate was rejected.
     */
    @Column(name = "response_message", length = 500)
    private String responseMessage;

    /**
     * FIX LocateReqID (client-generated request ID).
     */
    @Column(name = "fix_locate_req_id", length = 100)
    private String fixLocateReqId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum LocateStatus {
        PENDING,        // Locate request sent, waiting for response
        APPROVED,       // Locate approved, stock available
        REJECTED,       // Locate rejected, stock not available
        BORROWED,       // Stock successfully borrowed
        EXPIRED,        // Locate expired
        CANCELLED       // Locate request cancelled
    }
}

