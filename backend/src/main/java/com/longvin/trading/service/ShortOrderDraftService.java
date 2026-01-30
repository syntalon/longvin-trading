package com.longvin.trading.service;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for creating draft orders for shadow accounts when a short order is received.
 * Draft orders are created immediately but not sent until locate is approved and stock is borrowed.
 */
@Service
public class ShortOrderDraftService {

    private static final Logger log = LoggerFactory.getLogger(ShortOrderDraftService.class);

    private final OrderRepository orderRepository;
    private final AccountCacheService accountCacheService;
    private final FixClientProperties properties;

    public ShortOrderDraftService(OrderRepository orderRepository,
                                  AccountCacheService accountCacheService,
                                  FixClientProperties properties) {
        this.orderRepository = orderRepository;
        this.accountCacheService = accountCacheService;
        this.properties = properties;
    }

    /**
     * Create draft orders for all shadow accounts based on the primary order.
     * These orders will be sent after locate is approved and stock is borrowed.
     * 
     * @param primaryOrder The primary order from DAS Trader
     * @return List of created draft Order entities for shadow accounts
     */
    @Transactional
    public List<Order> createDraftOrdersForShadowAccounts(Order primaryOrder) {
        log.info("Creating draft orders for shadow accounts based on primary order: OrderID={}, Symbol={}, Side={}, Qty={}",
            primaryOrder.getFixOrderId(), primaryOrder.getSymbol(), primaryOrder.getSide(), primaryOrder.getOrderQty());

        // Get all active shadow accounts
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccounts();
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found, cannot create draft orders for order {}", primaryOrder.getFixOrderId());
            return new ArrayList<>();
        }

        List<Order> draftOrders = new ArrayList<>();

        // Create draft order for each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                Order draftOrder = createDraftOrder(primaryOrder, shadowAccount);
                draftOrders.add(draftOrder);
                log.info("Created draft order for shadow account {}: OrderID={}, Symbol={}, Qty={}",
                    shadowAccount.getAccountNumber(), draftOrder.getId(), draftOrder.getSymbol(), draftOrder.getOrderQty());
            } catch (Exception e) {
                log.error("Error creating draft order for shadow account {}: {}", shadowAccount.getAccountNumber(), e.getMessage(), e);
            }
        }

        log.info("Created {} draft orders for shadow accounts based on primary order {}", 
            draftOrders.size(), primaryOrder.getFixOrderId());

        return draftOrders;
    }

    /**
     * Create a draft order for a shadow account based on the primary order.
     */
    private Order createDraftOrder(Order primaryOrder, Account shadowAccount) {
        Order draftOrder = Order.builder()
            .account(shadowAccount)
            .primaryOrderClOrdId(primaryOrder.getFixClOrdId()) // Link to primary order by ClOrdID
            .symbol(primaryOrder.getSymbol())
            .side(primaryOrder.getSide())
            .ordType(primaryOrder.getOrdType())
            .timeInForce(primaryOrder.getTimeInForce())
            .orderQty(primaryOrder.getOrderQty())
            .price(primaryOrder.getPrice())
            .stopPx(primaryOrder.getStopPx())
            // Draft orders don't have FIX IDs yet - they'll be set when order is sent
            .fixOrderId(null)
            .fixClOrdId(null)
            .fixOrigClOrdId(null)
            // Status: Use '0' (NEW) but mark as draft conceptually
            // In practice, we'll track draft status by checking if fixClOrdId is null
            .ordStatus('0') // NEW status, but not yet sent
            .execType(null)
            .cumQty(BigDecimal.ZERO)
            .leavesQty(primaryOrder.getOrderQty())
            .build();

        draftOrder = orderRepository.save(draftOrder);

        return draftOrder;
    }

    /**
     * Get draft orders for a primary order (orders that haven't been sent yet).
     * Draft orders are identified by having null fixClOrdId and matching primaryOrderClOrdId.
     */
    @Transactional(readOnly = true)
    public List<Order> getDraftOrdersForPrimaryOrder(Order primaryOrder) {
        if (primaryOrder.getFixClOrdId() == null) {
            return new ArrayList<>();
        }

        return orderRepository.findByPrimaryOrderClOrdIdOrderByCreatedAtAsc(primaryOrder.getFixClOrdId())
            .stream()
            .filter(order -> 
                !order.getId().equals(primaryOrder.getId()) && // Exclude primary order
                order.getFixClOrdId() == null) // Draft orders don't have ClOrdID yet
            .toList();
    }

    /**
     * Check if an order is a draft order (not yet sent).
     */
    public boolean isDraftOrder(Order order) {
        return order.getFixClOrdId() == null || order.getFixClOrdId().isEmpty();
    }
}
