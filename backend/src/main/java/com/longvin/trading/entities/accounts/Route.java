package com.longvin.trading.entities.accounts;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Route entity representing a routing destination (ExDestination) for all order types.
 * 
 * Routes can be used for any order type (market, limit, stop, locate, etc.).
 * The routeType field is only relevant for locate orders:
 * - TYPE_0: Price inquiry first, then locate order fills directly
 * - TYPE_1: Locate order returns offer (OrdStatus=B), requires accept/reject
 * 
 * For non-locate routes, routeType should be null.
 */
@Entity
@Table(name = "routes", indexes = {
    @Index(name = "idx_route_broker_id", columnList = "broker_id"),
    @Index(name = "idx_route_name", columnList = "name"),
    @Index(name = "idx_route_active", columnList = "active"),
    @Index(name = "idx_route_broker_name", columnList = "broker_id,name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The broker this route belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_id", nullable = false)
    private Broker broker;

    /**
     * Route name (ExDestination) - e.g., "LOCATE", "TESTSL", "OPAL", "DAST", "NYSE", "NASDAQ".
     * Must be unique per broker.
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Route type: TYPE_0 or TYPE_1 (only relevant for locate routes).
     * - TYPE_0: Price inquiry first, then locate order fills directly
     * - TYPE_1: Locate order returns offer (OrdStatus=B), requires accept/reject
     * 
     * This field is nullable - it should only be set for locate routes.
     * For regular trading routes (market, limit, etc.), this should be null.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "route_type", length = 20)
    private LocateRouteType routeType;

    /**
     * Description of the route.
     */
    @Column(length = 500)
    private String description;

    /**
     * Whether this route is active and available for use.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Priority/order for route selection (lower number = higher priority).
     * Used when multiple routes are available for a symbol.
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

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
     * Route type enumeration (only relevant for locate routes).
     */
    public enum LocateRouteType {
        TYPE_0,  // Price inquiry first, then locate order fills directly
        TYPE_1    // Locate order returns offer, then accept/reject, then fills
    }
}

