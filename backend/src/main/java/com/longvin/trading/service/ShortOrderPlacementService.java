package com.longvin.trading.service;

import com.longvin.trading.config.FixClientProperties;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for placing sell short orders for shadow accounts after locate is approved and stock is borrowed.
 * This service listens for locate success events and sends the actual orders.
 */
@Service
public class ShortOrderPlacementService {

    private static final Logger log = LoggerFactory.getLogger(ShortOrderPlacementService.class);

    private final OrderRepository orderRepository;
    private final ShortOrderDraftService draftService;
    private final FixClientProperties properties;
    private final ShortSellAllocationService allocationService;

    public ShortOrderPlacementService(OrderRepository orderRepository,
                                     ShortOrderDraftService draftService,
                                     FixClientProperties properties,
                                     ShortSellAllocationService allocationService) {
        this.orderRepository = orderRepository;
        this.draftService = draftService;
        this.properties = properties;
        this.allocationService = allocationService;
    }

    /**
     * Place sell short orders for all shadow accounts after locate is approved and stock is borrowed.
     * This should be called after locate success.
     * 
     * @param primaryOrder The primary order that triggered the locate request
     * @param approvedQty The approved quantity from the locate
     * @param initiatorSessionID The initiator session to send orders
     */
    @Transactional
    public void placeShadowOrdersAfterLocate(Order primaryOrder,
                                             BigDecimal approvedQty,
                                             SessionID initiatorSessionID) {
        log.info("Placing shadow orders after locate approval for primary order: OrderID={}, Symbol={}, Qty={}",
            primaryOrder.getFixOrderId(), primaryOrder.getSymbol(), primaryOrder.getOrderQty());

        // Get draft orders for shadow accounts
        List<Order> draftOrders = draftService.getDraftOrdersForPrimaryOrder(primaryOrder);
        if (draftOrders.isEmpty()) {
            log.warn("No draft orders found for primary order {}, cannot place shadow orders", primaryOrder.getFixOrderId());
            return;
        }

        Map<UUID, BigDecimal> allocations = allocationService.calculateShadowAllocations(primaryOrder, draftOrders, approvedQty);
        if (allocations.isEmpty()) {
            log.warn("No shadow allocations computed for primary order {} despite {} draft orders", primaryOrder.getFixOrderId(), draftOrders.size());
        }

        log.info("Found {} draft orders to send for primary order {}", draftOrders.size(), primaryOrder.getFixOrderId());

        // Send order for each shadow account
        for (Order draftOrder : draftOrders) {
            BigDecimal targetQty = allocations.getOrDefault(draftOrder.getId(), BigDecimal.ZERO);
            if (targetQty == null || targetQty.signum() <= 0) {
                log.info("Skipping shadow account {} due to zero allocation", draftOrder.getAccount().getAccountNumber());
                continue;
            }
            draftOrder.setOrderQty(targetQty);
            draftOrder.setLeavesQty(targetQty);
            try {
                sendShadowOrder(draftOrder, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error sending shadow order for account {}: {}",
                    draftOrder.getAccount().getAccountNumber(), e.getMessage(), e);
            }
        }

        log.info("Completed placing shadow orders for primary order {}", primaryOrder.getFixOrderId());
    }

    /**
     * Send a sell short order for a shadow account.
     */
    private void sendShadowOrder(Order draftOrder, SessionID initiatorSessionID) throws SessionNotFound, FieldNotFound {
        Session session = Session.lookupSession(initiatorSessionID);
        if (session == null || !session.isLoggedOn()) {
            log.error("Cannot send shadow order: session {} is not logged on", initiatorSessionID);
            return;
        }

        Account shadowAccount = draftOrder.getAccount();
        String accountNumber = shadowAccount.getAccountNumber();

        // Generate ClOrdID for shadow order
        String clOrdId = generateShadowClOrdId(draftOrder);

        // Build NewOrderSingle from draft order
        NewOrderSingle order = buildNewOrderSingle(draftOrder, clOrdId, accountNumber);

        // Send order
        Session.sendToTarget(order, initiatorSessionID);

        // Update draft order with ClOrdID
        draftOrder.setFixClOrdId(clOrdId);
        draftOrder.setOrdStatus('0'); // NEW
        orderRepository.save(draftOrder);

        log.info("Sent sell short order for shadow account {}: ClOrdID={}, Symbol={}, Side={}, Qty={}",
            accountNumber, clOrdId, draftOrder.getSymbol(), draftOrder.getSide(), draftOrder.getOrderQty());
    }

    /**
     * Build NewOrderSingle from draft Order entity.
     */
    private NewOrderSingle buildNewOrderSingle(Order draftOrder, String clOrdId, String accountNumber) throws FieldNotFound {
        NewOrderSingle order = new NewOrderSingle();
        
        order.set(new ClOrdID(clOrdId));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        order.set(new Symbol(draftOrder.getSymbol()));
        
        if (draftOrder.getSide() != null) {
            order.set(new Side(draftOrder.getSide()));
        }
        
        if (draftOrder.getOrdType() != null) {
            order.set(new OrdType(draftOrder.getOrdType()));
        } else {
            // Default to MARKET if not specified
            order.set(new OrdType(OrdType.MARKET));
        }
        
        if (draftOrder.getOrderQty() != null) {
            order.set(new OrderQty(draftOrder.getOrderQty().doubleValue()));
        }
        
        if (draftOrder.getPrice() != null && draftOrder.getOrdType() != null) {
            char ordType = draftOrder.getOrdType();
            if (ordType == OrdType.LIMIT || ordType == OrdType.STOP_LIMIT) {
                order.set(new Price(draftOrder.getPrice().doubleValue()));
            }
        }
        
        if (draftOrder.getStopPx() != null && draftOrder.getOrdType() != null) {
            char ordType = draftOrder.getOrdType();
            if (ordType == OrdType.STOP_STOP_LOSS || ordType == OrdType.STOP_LIMIT) {
                order.set(new StopPx(draftOrder.getStopPx().doubleValue()));
            }
        }
        
        if (draftOrder.getTimeInForce() != null) {
            order.set(new TimeInForce(draftOrder.getTimeInForce()));
        } else {
            order.set(new TimeInForce(TimeInForce.DAY));
        }
        
        // Set account field (use quickfix.field.Account, not entity)
        order.set(new quickfix.field.Account(accountNumber));
        
        // Set transaction time
        order.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));

        return order;
    }

    /**
     * Generate ClOrdID for shadow order.
     */
    private String generateShadowClOrdId(Order draftOrder) {
        String primaryOrderId = draftOrder.getOrderGroup() != null && draftOrder.getOrderGroup().getPrimaryOrder() != null
            ? draftOrder.getOrderGroup().getPrimaryOrder().getFixOrderId()
            : "UNKNOWN";
        
        String accountNumber = draftOrder.getAccount().getAccountNumber();
        String prefix = properties.getClOrdIdPrefix();
        
        // Format: PREFIX-N-SHADOW-ACCOUNT-PRIMARYORDERID
        String clOrdId = String.format("%sN-%s-%s", prefix, accountNumber, primaryOrderId);
        
        // ClOrdID max length is typically 20 characters
        if (clOrdId.length() > 20) {
            clOrdId = clOrdId.substring(0, 20);
        }
        
        return clOrdId;
    }

    /**
     * Handle locate success - place shadow orders.
     * This method should be called when locate is approved and stock is borrowed.
     */
    public void onLocateSuccess(String primaryOrderId, BigDecimal approvedQty, String locateId, SessionID initiatorSessionID) {
        log.info("Locate success for primary order {}: ApprovedQty={}, LocateID={}", 
            primaryOrderId, approvedQty, locateId);

        // Find primary order
        Optional<Order> primaryOrderOpt = orderRepository.findByFixOrderId(primaryOrderId);
        if (primaryOrderOpt.isEmpty()) {
            log.warn("Primary order not found for OrderID={}, cannot place shadow orders", primaryOrderId);
            return;
        }

        Order primaryOrder = primaryOrderOpt.get();

        // Place shadow orders
        placeShadowOrdersAfterLocate(primaryOrder, approvedQty, initiatorSessionID);
    }
}
