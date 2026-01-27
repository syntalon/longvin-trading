package com.longvin.trading.entities.copy;

import com.longvin.trading.entities.accounts.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * CopyRule entity defining how orders from a primary account should be copied to a shadow account.
 * 
 * This allows fine-grained control over:
 * - Which orders to copy (order types, routes)
 * - When to copy (on new order, on fill, on partial fill)
 * - How much to copy (ratio, multiplier, fixed quantity)
 * - Which route to use when copying (copyRoute field)
 * 
 * Route Types:
 * 1. Transaction Routes (routes table): General execution routes like LOCATE, NYSE, NASDAQ, etc.
 * 2. Copy Route (copyRoute field): Route to use when copying regular orders (market, limit, stop, etc.)
 *    - If set, the copied order will use this route instead of the primary account's route
 *    - If null, the copied order will use the same route as the primary account
 * 3. Locate Route (locateRoute field): Route to use when copying locate orders
 *    - If set, locate copy orders will use this route
 *    - If null, falls back to copyRoute, then to primary account's route
 */
@Entity
@Table(name = "copy_rules", indexes = {
    @Index(name = "idx_copy_rule_primary_account", columnList = "primary_account_id"),
    @Index(name = "idx_copy_rule_shadow_account", columnList = "shadow_account_id"),
    @Index(name = "idx_copy_rule_active", columnList = "active"),
    @Index(name = "idx_copy_rule_primary_shadow", columnList = "primary_account_id,shadow_account_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CopyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * Primary account (source of orders to copy).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_account_id", nullable = false)
    private Account primaryAccount;

    /**
     * Shadow account (destination for copied orders).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shadow_account_id", nullable = false)
    private Account shadowAccount;

    /**
     * Copy ratio type: PERCENTAGE, MULTIPLIER, or FIXED_QUANTITY.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ratio_type", nullable = false, length = 20)
    @Builder.Default
    private CopyRatioType ratioType = CopyRatioType.MULTIPLIER;

    /**
     * Copy ratio value.
     * - For PERCENTAGE: 0.5 = 50%, 1.0 = 100%, 2.0 = 200%
     * - For MULTIPLIER: 0.5 = half, 1.0 = same, 2.0 = double
     * - For FIXED_QUANTITY: exact quantity to copy (ignores primary order quantity)
     */
    @Column(name = "ratio_value", precision = 18, scale = 8, nullable = false)
    @Builder.Default
    private BigDecimal ratioValue = BigDecimal.ONE; // Default 1:1 copy

    /**
     * Order types to copy (comma-separated list of OrdType values).
     * Examples: "1,2" for MARKET and LIMIT, "1,2,3,4" for all types.
     * If null or empty, copy all order types.
     */
    @Column(name = "order_types", length = 50)
    private String orderTypes; // e.g., "1,2,3" for MARKET, LIMIT, STOP

    /**
     * Route (ExDestination) to use when copying regular orders TO the shadow account.
     * This is the target route for non-locate copied orders (market, limit, stop, etc.).
     * If null, use the same route as the primary account order.
     * Examples: "NYSE", "NASDAQ", "OPAL", "DAST"
     */
    @Column(name = "copy_route", length = 100)
    private String copyRoute; // e.g., "NYSE" - route to use for regular copy orders

    /**
     * Route (ExDestination) to use when copying locate orders TO the shadow account.
     * This is the target route specifically for locate orders.
     * If null, use copy_route if set, otherwise use the same route as the primary account order.
     * Examples: "LOCATE", "TESTSL"
     */
    @Column(name = "locate_route", length = 100)
    private String locateRoute; // e.g., "LOCATE" - route to use for locate copy orders

    /**
     * Broker to use when copying orders TO the shadow account.
     * If null, use the shadow account's broker.
     * Examples: "OPAL", "DAST", "DEFAULT"
     */
    @Column(name = "copy_broker", length = 100)
    private String copyBroker; // e.g., "OPAL" - broker to use for copy orders

    /**
     * Minimum quantity threshold - only copy if primary order quantity >= this value.
     * If null, no minimum threshold.
     */
    @Column(name = "min_quantity", precision = 18, scale = 8)
    private BigDecimal minQuantity;

    /**
     * Maximum quantity threshold - only copy if primary order quantity <= this value.
     * If null, no maximum threshold.
     */
    @Column(name = "max_quantity", precision = 18, scale = 8)
    private BigDecimal maxQuantity;

    /**
     * Priority when multiple rules match (lower number = higher priority).
     * Used when a primary account has multiple shadow accounts with different rules.
     */
    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    /**
     * Whether this copy rule is active.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Description of this copy rule.
     */
    @Column(length = 500)
    private String description;

    /**
     * JSON configuration map for flexible rule settings.
     * Can store any additional configuration as key-value pairs.
     * Example: {"customField1": "value1", "customField2": 123}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    private Map<String, Object> config;

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
     * Copy ratio type enumeration.
     */
    public enum CopyRatioType {
        PERCENTAGE,      // Ratio value is a percentage (0.5 = 50%, 1.0 = 100%)
        MULTIPLIER,      // Ratio value is a multiplier (0.5 = half, 1.0 = same, 2.0 = double)
        FIXED_QUANTITY   // Ratio value is a fixed quantity (ignores primary order quantity)
    }


    /**
     * Helper method to check if an order type should be copied.
     */
    public boolean shouldCopyOrderType(Character ordType) {
        if (orderTypes == null || orderTypes.isBlank()) {
            return true; // Copy all order types if not specified
        }
        String ordTypeStr = String.valueOf(ordType);
        return orderTypes.contains(ordTypeStr);
    }

    /**
     * Get the route to use when copying to the shadow account.
     * For locate orders, uses locateRoute if set, otherwise falls back to copyRoute or primary route.
     * For regular orders, uses copyRoute if set, otherwise uses primary route.
     * 
     * @param primaryRoute The route used by the primary account
     * @param isLocateOrder Whether this is a locate order
     * @return The route to use for the copy order
     */
    public String getTargetRoute(String primaryRoute, boolean isLocateOrder) {
        if (isLocateOrder) {
            // For locate orders, prefer locateRoute
            if (locateRoute != null && !locateRoute.isBlank()) {
                return locateRoute;
            }
            // Fall back to copyRoute or primary route
            if (copyRoute != null && !copyRoute.isBlank()) {
                return copyRoute;
            }
        } else {
            // For regular orders, use copyRoute
            if (copyRoute != null && !copyRoute.isBlank()) {
                return copyRoute;
            }
        }
        // Default: use the same route as primary account
        return primaryRoute;
    }

    /**
     * Get the route to use when copying to the shadow account (backward compatibility).
     * Assumes it's not a locate order.
     * 
     * @param primaryRoute The route used by the primary account
     * @return The route to use for the copy order
     */
    public String getTargetRoute(String primaryRoute) {
        return getTargetRoute(primaryRoute, false);
    }


    /**
     * Calculate the quantity to copy based on the primary order quantity.
     */
    public BigDecimal calculateCopyQuantity(BigDecimal primaryQuantity) {
        if (primaryQuantity == null || primaryQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal copyQty;
        switch (ratioType) {
            case PERCENTAGE:
                copyQty = primaryQuantity.multiply(ratioValue).divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.HALF_UP);
                break;
            case MULTIPLIER:
                copyQty = primaryQuantity.multiply(ratioValue);
                break;
            case FIXED_QUANTITY:
                copyQty = ratioValue;
                break;
            default:
                copyQty = primaryQuantity; // Default to 1:1
        }

        // Round to nearest integer (quantities are typically whole numbers)
        return copyQty.setScale(0, java.math.RoundingMode.HALF_UP);
    }
}

