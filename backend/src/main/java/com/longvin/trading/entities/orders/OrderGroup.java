package com.longvin.trading.entities.orders;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OrderGroup represents a group of related orders: one primary order and its shadow orders.
 * This allows tracking which shadow orders belong to which primary order.
 */
@Entity
@Table(name = "order_groups", indexes = {
    @Index(name = "idx_order_group_strategy_key", columnList = "strategy_key"),
    @Index(name = "idx_order_group_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OrderGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private UUID id;

    /**
     * Strategy or group identifier (e.g., "OPAL_GROUP_1", "DAS_STRATEGY_A")
     * Can be used to group orders by trading strategy or replication strategy.
     */
    @Column(name = "strategy_key", length = 100)
    private String strategyKey;

    /**
     * The primary order in this group (the original order from DAS Trader).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_order_id")
    private Order primaryOrder;

    /**
     * All orders in this group (primary + shadows).
     */
    @OneToMany(mappedBy = "orderGroup", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Order> orders = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Helper method to add an order to this group.
     */
    public void addOrder(Order order) {
        if (!orders.contains(order)) {
            orders.add(order);
            order.setOrderGroup(this);
        }
    }
}

