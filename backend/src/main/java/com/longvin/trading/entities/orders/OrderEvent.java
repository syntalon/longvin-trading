package com.longvin.trading.entities.orders;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OrderEvent represents an immutable event from a FIX ExecutionReport.
 * Every ExecutionReport becomes a row in this table - events are never updated, only appended.
 * This provides a complete audit trail of all order state changes.
 */
@Entity
@Table(name = "order_events", indexes = {
    @Index(name = "idx_order_event_order_id", columnList = "order_id"),
    @Index(name = "idx_order_event_fix_exec_id", columnList = "fix_exec_id"),
    @Index(name = "idx_order_event_event_time", columnList = "event_time"),
    @Index(name = "idx_order_event_exec_type", columnList = "exec_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private UUID id;

    /**
     * The order this event belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * FIX ExecID from the ExecutionReport (unique per execution).
     */
    @Column(name = "fix_exec_id", nullable = false, length = 100)
    private String fixExecId;

    /**
     * Execution type from ExecutionReport.
     * Values: NEW, PARTIAL_FILL, FILL, DONE_FOR_DAY, CANCELED, REPLACED, etc.
     */
    @Column(name = "exec_type", nullable = false, length = 1)
    private Character execType;

    /**
     * Order status from ExecutionReport.
     * Values: NEW, PARTIALLY_FILLED, FILLED, DONE_FOR_DAY, CANCELED, etc.
     */
    @Column(name = "ord_status", nullable = false, length = 1)
    private Character ordStatus;

    /**
     * FIX OrderID from ExecutionReport.
     */
    @Column(name = "fix_order_id", length = 100)
    private String fixOrderId;

    /**
     * FIX ClOrdID from ExecutionReport.
     */
    @Column(name = "fix_cl_ord_id", length = 100)
    private String fixClOrdId;

    /**
     * Original ClOrdID (for replaced/canceled orders).
     */
    @Column(name = "fix_orig_cl_ord_id", length = 100)
    private String fixOrigClOrdId;

    /**
     * Symbol.
     */
    @Column(length = 50)
    private String symbol;

    /**
     * Side: BUY, SELL, SELL_SHORT, SELL_SHORT_EXEMPT
     */
    @Column(length = 1)
    private Character side;

    /**
     * Order type.
     */
    @Column(name = "ord_type", length = 1)
    private Character ordType;

    /**
     * Time in force.
     */
    @Column(name = "time_in_force", length = 1)
    private Character timeInForce;

    /**
     * Order quantity.
     */
    @Column(name = "order_qty", precision = 18, scale = 8)
    private BigDecimal orderQty;

    /**
     * Limit price.
     */
    @Column(precision = 18, scale = 8)
    private BigDecimal price;

    /**
     * Stop price.
     */
    @Column(name = "stop_px", precision = 18, scale = 8)
    private BigDecimal stopPx;

    /**
     * Last execution price (from this ExecutionReport).
     */
    @Column(name = "last_px", precision = 18, scale = 8)
    private BigDecimal lastPx;

    /**
     * Last execution quantity (from this ExecutionReport).
     */
    @Column(name = "last_qty", precision = 18, scale = 8)
    private BigDecimal lastQty;

    /**
     * Cumulative quantity executed (as of this ExecutionReport).
     */
    @Column(name = "cum_qty", precision = 18, scale = 8)
    private BigDecimal cumQty;

    /**
     * Leaves quantity (remaining to be filled, as of this ExecutionReport).
     */
    @Column(name = "leaves_qty", precision = 18, scale = 8)
    private BigDecimal leavesQty;

    /**
     * Average execution price (as of this ExecutionReport).
     */
    @Column(name = "avg_px", precision = 18, scale = 8)
    private BigDecimal avgPx;

    /**
     * Account from ExecutionReport (if present).
     */
    @Column(length = 100)
    private String account;

    /**
     * Transaction time from ExecutionReport.
     */
    @Column(name = "transact_time")
    private LocalDateTime transactTime;

    /**
     * Text message from ExecutionReport (if present).
     */
    @Column(length = 500)
    private String text;

    /**
     * Timestamp when this event was received and stored.
     * This is when we processed the ExecutionReport, not the FIX TransactTime.
     */
    @Column(name = "event_time", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime eventTime = LocalDateTime.now();

    /**
     * Raw FIX message (optional, for debugging/audit).
     */
    @Lob
    @Column(name = "raw_fix_message")
    private String rawFixMessage;

    /**
     * Session ID where this event came from (e.g., "FIX.4.2:OS111->DAST").
     */
    @Column(name = "session_id", length = 200)
    private String sessionId;
}

