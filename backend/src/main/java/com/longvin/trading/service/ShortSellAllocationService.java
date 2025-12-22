package com.longvin.trading.service;

import com.longvin.trading.entities.orders.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Computes proportional shadow-account allocations for short selling workflows.
 * Uses draft order quantities (which encode per-account ratios) to split any
 * approved locate quantity across child orders without exceeding availability.
 */
@Service
public class ShortSellAllocationService {

    private static final Logger log = LoggerFactory.getLogger(ShortSellAllocationService.class);

    /**
     * Calculate total locate requirement as primary order qty plus aggregated shadow demand.
     */
    public BigDecimal calculateTotalLocateQuantity(Order primaryOrder, List<Order> shadowDrafts) {
        BigDecimal primaryQty = defaultQty(primaryOrder != null ? primaryOrder.getOrderQty() : null);
        return primaryQty.add(sumDesired(shadowDrafts));
    }

    /**
     * Build per-shadow allocations for the given approved locate quantity.
     * The first portion of the approval covers the primary order; the remainder
     * is distributed across draft orders proportionally to their desired qty.
     */
    public Map<UUID, BigDecimal> calculateShadowAllocations(Order primaryOrder,
                                                            List<Order> shadowDrafts,
                                                            BigDecimal approvedQty) {
        Map<UUID, BigDecimal> allocations = new LinkedHashMap<>();
        if (shadowDrafts == null || shadowDrafts.isEmpty()) {
            return allocations;
        }

        BigDecimal primaryQty = defaultQty(primaryOrder != null ? primaryOrder.getOrderQty() : null);
        BigDecimal availableForShadows = defaultQty(approvedQty).subtract(primaryQty);
        if (availableForShadows.signum() <= 0) {
            log.warn("Locate approval {} does not cover primary quantity {}. No shadow orders will be placed.",
                    approvedQty, primaryQty);
            shadowDrafts.forEach(order -> allocations.put(order.getId(), BigDecimal.ZERO));
            return allocations;
        }

        BigDecimal desiredTotal = sumDesired(shadowDrafts);
        if (desiredTotal.signum() <= 0) {
            log.debug("Shadow draft demand is zero for primary order {}", primaryOrder != null ? primaryOrder.getFixOrderId() : "N/A");
            shadowDrafts.forEach(order -> allocations.put(order.getId(), BigDecimal.ZERO));
            return allocations;
        }

        if (availableForShadows.compareTo(desiredTotal) >= 0) {
            shadowDrafts.forEach(order -> allocations.put(order.getId(), defaultQty(order.getOrderQty())));
            return allocations;
        }

        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < shadowDrafts.size(); i++) {
            Order draft = shadowDrafts.get(i);
            BigDecimal desired = defaultQty(draft.getOrderQty());
            BigDecimal share;
            if (i == shadowDrafts.size() - 1) {
                share = availableForShadows.subtract(assigned);
                if (share.signum() < 0) {
                    share = BigDecimal.ZERO;
                }
            } else {
                share = availableForShadows.multiply(desired)
                        .divide(desiredTotal, 8, RoundingMode.DOWN);
                assigned = assigned.add(share);
            }
            allocations.put(draft.getId(), share);
        }

        log.info("Allocated {} shares across {} shadow accounts (desired total {}, primary covered {}).",
                availableForShadows, shadowDrafts.size(), desiredTotal, primaryQty);
        return allocations;
    }

    private BigDecimal sumDesired(List<Order> shadowDrafts) {
        if (shadowDrafts == null) {
            return BigDecimal.ZERO;
        }
        return shadowDrafts.stream()
                .map(order -> defaultQty(order.getOrderQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal defaultQty(BigDecimal qty) {
        return qty == null ? BigDecimal.ZERO : qty;
    }
}

