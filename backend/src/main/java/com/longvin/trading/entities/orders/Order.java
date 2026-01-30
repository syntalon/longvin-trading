package com.longvin.trading.entities.orders;

import com.longvin.trading.entities.accounts.Account;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Order entity representing the current state of an order.
 * This is derived from OrderEvent records and updated as new events arrive.
 * Shadow orders are linked to primary orders via primaryOrderClOrdId.
 */
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_order_account_id", columnList = "account_id"),
    @Index(name = "idx_order_fix_order_id", columnList = "fix_order_id"),
    @Index(name = "idx_order_fix_cl_ord_id", columnList = "fix_cl_ord_id"),
    @Index(name = "idx_order_created_at", columnList = "created_at"),
    @Index(name = "idx_order_updated_at", columnList = "updated_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private UUID id;

    /**
     * The account this order belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * ClOrdID of the primary order (for shadow orders).
     * This allows shadow orders to reference their primary order without requiring OrderGroup.
     */
    @Column(name = "primary_order_cl_ord_id", length = 100)
    private String primaryOrderClOrdId;

    /**
     * FIX OrderID from the broker/exchange (stored as data, not primary key).
     */
    @Column(name = "fix_order_id", length = 100)
    private String fixOrderId;

    /**
     * FIX ClOrdID (Client Order ID) from the original NewOrderSingle.
     */
    @Column(name = "fix_cl_ord_id", length = 100)
    private String fixClOrdId;

    /**
     * Original ClOrdID (for replaced/canceled orders).
     */
    @Column(name = "fix_orig_cl_ord_id", length = 100)
    private String fixOrigClOrdId;

    /**
     * Symbol being traded.
     */
    @Column(nullable = false, length = 50)
    private String symbol;

    /**
     * Side: BUY, SELL, SELL_SHORT, SELL_SHORT_EXEMPT
     */
    @Column(nullable = false, length = 1)
    private Character side;

    /**
     * Order type: MARKET, LIMIT, STOP, STOP_LIMIT, etc.
     */
    @Column(name = "ord_type", length = 1)
    private Character ordType;

    /**
     * Time in force: DAY, GTC, IOC, etc.
     */
    @Column(name = "time_in_force", length = 1)
    private Character timeInForce;

    /**
     * Order quantity.
     */
    @Column(name = "order_qty", precision = 18, scale = 8)
    private BigDecimal orderQty;

    /**
     * Limit price (for LIMIT orders).
     */
    @Column(precision = 18, scale = 8)
    private BigDecimal price;

    /**
     * Stop price (for STOP/STOP_LIMIT orders).
     */
    @Column(name = "stop_px", precision = 18, scale = 8)
    private BigDecimal stopPx;

    /**
     * Route (ExDestination) used for this order.
     */
    @Column(name = "ex_destination", length = 50)
    private String exDestination;

    /**
     * Current execution type (from last ExecutionReport).
     * Values: NEW, PARTIAL_FILL, FILL, DONE_FOR_DAY, CANCELED, REPLACED, etc.
     */
    @Column(name = "exec_type", length = 1)
    private Character execType;

    /**
     * Current order status (from last ExecutionReport).
     * Values: NEW, PARTIALLY_FILLED, FILLED, DONE_FOR_DAY, CANCELED, etc.
     */
    @Column(name = "ord_status", nullable = false, length = 1)
    @Builder.Default
    private Character ordStatus = '0'; // NEW

    /**
     * Cumulative quantity executed.
     */
    @Column(name = "cum_qty", precision = 18, scale = 8)
    private BigDecimal cumQty;

    /**
     * Leaves quantity (remaining to be filled).
     */
    @Column(name = "leaves_qty", precision = 18, scale = 8)
    private BigDecimal leavesQty;

    /**
     * Average execution price.
     */
    @Column(name = "avg_px", precision = 18, scale = 8)
    private BigDecimal avgPx;

    /**
     * Last execution price.
     */
    @Column(name = "last_px", precision = 18, scale = 8)
    private BigDecimal lastPx;

    /**
     * Last execution quantity.
     */
    @Column(name = "last_qty", precision = 18, scale = 8)
    private BigDecimal lastQty;

    /**
     * All events for this order (immutable history).
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderEvent> events = new ArrayList<>();

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

    /**
     * Helper method to add an event to this order.
     */
    public void addEvent(OrderEvent event) {
        if (!events.contains(event)) {
            events.add(event);
            event.setOrder(this);
        }
    }
}

