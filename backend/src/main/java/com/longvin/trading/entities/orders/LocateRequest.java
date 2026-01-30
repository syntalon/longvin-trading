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
     * The primary order that triggered this locate request (for reference).
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
     * Response message or reason if locate was rejected.
     */
    @Column(name = "response_message", length = 500)
    private String responseMessage;

    /**
     * FIX QuoteReqID (tag 131) - client-generated request ID, echoed back in response.
     * This is the ID used in Short Locate Quote Request (MsgType=R) and Response (MsgType=S).
     */
    @Column(name = "fix_quote_req_id", length = 100)
    private String fixQuoteReqId;

    /**
     * Locate route (tag 100) - routing destination for locate request.
     */
    @Column(name = "locate_route", length = 100)
    private String locateRoute;

    /**
     * Offer price per share (tag 133) - fee per share from locate quote response.
     */
    @Column(name = "offer_px", precision = 18, scale = 8)
    private BigDecimal offerPx;

    /**
     * Offer size (tag 135) - available shares from locate quote response.
     * <= 0 means locate failed.
     */
    @Column(name = "offer_size", precision = 18, scale = 8)
    private BigDecimal offerSize;

    /**
     * Approved quantity - actual quantity approved for borrowing (may be less than requested).
     */
    @Column(name = "approved_qty", precision = 18, scale = 8)
    private BigDecimal approvedQty;

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
        PENDING,                    // Quote request sent, waiting for response
        APPROVED_FULL,              // Full quantity approved
        APPROVED_PARTIAL,           // Partial quantity approved (less than requested)
        REJECTED,                  // Locate rejected (OfferSize <= 0)
        EXPIRED,                    // Locate expired
        CANCELLED                   // Locate request cancelled
    }
}

