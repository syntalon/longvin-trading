package com.longvin.trading.rest;

import com.longvin.trading.dto.orders.OrderDto;
import com.longvin.trading.dto.orders.OrderEventDto;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.orders.Order;
import com.longvin.trading.entities.orders.OrderEvent;
import com.longvin.trading.repository.AccountRepository;
import com.longvin.trading.repository.OrderEventRepository;
import com.longvin.trading.repository.OrderRepository;
import com.longvin.trading.service.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for Order search and query operations.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final OrderEventRepository orderEventRepository;
    private final RouteService routeService;

    public OrderController(OrderRepository orderRepository, 
                          AccountRepository accountRepository,
                          OrderEventRepository orderEventRepository,
                          RouteService routeService) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.orderEventRepository = orderEventRepository;
        this.routeService = routeService;
    }

    /**
     * Search orders with various filters.
     */
    @GetMapping
    public ResponseEntity<List<OrderDto>> searchOrders(
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String fixClOrdId,
            @RequestParam(required = false) String fixOrderId,
            @RequestParam(required = false) Character ordStatus,
            @RequestParam(required = false) Character execType,
            @RequestParam(required = false) Boolean isCopyOrder,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        try {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<Order> ordersPage;
            
            // Find account if accountNumber is provided
            Account account = null;
            if (accountNumber != null && !accountNumber.isBlank()) {
                Optional<Account> accountOpt = accountRepository.findByAccountNumber(accountNumber);
                if (accountOpt.isPresent()) {
                    account = accountOpt.get();
                } else {
                    log.warn("Account not found: {}", accountNumber);
                    return ResponseEntity.ok(List.of());
                }
            } else if (accountId != null) {
                Optional<Account> accountOpt = accountRepository.findById(accountId);
                if (accountOpt.isPresent()) {
                    account = accountOpt.get();
                } else {
                    log.warn("Account not found: {}", accountId);
                    return ResponseEntity.ok(List.of());
                }
            }
            
            // Build query based on filters
            if (account != null && symbol != null && !symbol.isBlank()) {
                ordersPage = orderRepository.findBySymbolAndAccountOrderByCreatedAtDesc(symbol, account, pageable);
            } else if (account != null) {
                ordersPage = orderRepository.findByAccountOrderByCreatedAtDesc(account, pageable);
            } else if (symbol != null && !symbol.isBlank()) {
                ordersPage = orderRepository.findBySymbolOrderByCreatedAtDesc(symbol, pageable);
            } else if (fixClOrdId != null && !fixClOrdId.isBlank()) {
                Optional<Order> orderOpt = orderRepository.findByFixClOrdId(fixClOrdId);
                if (orderOpt.isPresent()) {
                    ordersPage = Page.empty();
                    // Return single order as list
                    List<OrderDto> dtos = List.of(toDto(orderOpt.get()));
                    return ResponseEntity.ok(dtos);
                } else {
                    return ResponseEntity.ok(List.of());
                }
            } else if (fixOrderId != null && !fixOrderId.isBlank()) {
                Optional<Order> orderOpt = orderRepository.findByFixOrderId(fixOrderId);
                if (orderOpt.isPresent()) {
                    ordersPage = Page.empty();
                    // Return single order as list
                    List<OrderDto> dtos = List.of(toDto(orderOpt.get()));
                    return ResponseEntity.ok(dtos);
                } else {
                    return ResponseEntity.ok(List.of());
                }
            } else if (ordStatus != null) {
                ordersPage = orderRepository.findByOrdStatus(ordStatus, pageable);
            } else if (execType != null) {
                ordersPage = orderRepository.findByExecType(execType, pageable);
            } else {
                // Default: get orders from current day (last 24 hours)
                // If startDate/endDate are provided, use them; otherwise default to current day
                LocalDateTime end = endDate != null ? LocalDateTime.parse(endDate) : LocalDateTime.now();
                LocalDateTime start = startDate != null ? LocalDateTime.parse(startDate) : end.minusDays(1);
                ordersPage = orderRepository.findByCreatedAtBetween(start, end, pageable);
            }
            
            // Apply date filter to all queries (if date filters provided, or default to current day)
            LocalDateTime filterStart;
            LocalDateTime filterEnd;
            
            if (startDate != null || endDate != null) {
                // Use provided date filters
                filterEnd = endDate != null ? LocalDateTime.parse(endDate) : LocalDateTime.now();
                filterStart = startDate != null ? LocalDateTime.parse(startDate) : filterEnd.minusDays(1);
            } else {
                // Default: current day (last 24 hours)
                filterEnd = LocalDateTime.now();
                filterStart = filterEnd.minusDays(1);
            }
            
            // Filter results by date range
            List<Order> filteredOrders = ordersPage.getContent().stream()
                    .filter(order -> {
                        LocalDateTime orderTime = order.getCreatedAt();
                        return orderTime != null && 
                               !orderTime.isBefore(filterStart) &&
                               !orderTime.isAfter(filterEnd);
                    })
                    .collect(Collectors.toList());
            
            // Create a new page with filtered content
            ordersPage = new org.springframework.data.domain.PageImpl<>(
                    filteredOrders, 
                    pageable, 
                    filteredOrders.size()
            );
            
            // Filter by isCopyOrder if specified
            List<OrderDto> dtos = ordersPage.getContent().stream()
                    .map(this::toDto)
                    .filter(dto -> {
                        if (isCopyOrder != null) {
                            return dto.getIsCopyOrder().equals(isCopyOrder);
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("Error searching orders", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get order by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrderById(@PathVariable UUID id) {
        return orderRepository.findById(id)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all orders (primary + shadows) by primary order ClOrdID.
     * Returns the primary order and all its shadow orders.
     */
    @GetMapping("/by-primary/{primaryClOrdId}")
    public ResponseEntity<List<OrderDto>> getOrdersByPrimaryClOrdId(@PathVariable String primaryClOrdId) {
        List<Order> orders = orderRepository.findByPrimaryOrderClOrdId(primaryClOrdId);
        List<OrderDto> dtos = orders.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get all events for an order by order ID.
     */
    @GetMapping("/{orderId}/events")
    public ResponseEntity<List<OrderEventDto>> getOrderEventsByOrderId(@PathVariable UUID orderId) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Order order = orderOpt.get();
        List<OrderEvent> events = orderEventRepository.findByOrderOrderByEventTimeAsc(order);
        List<OrderEventDto> dtos = events.stream()
                .map(this::eventToDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get all events for an order by ClOrdID (event-driven: events can exist without order).
     * Includes events where the ClOrdID matches or where OrigClOrdID matches (for replace events with temporary ClOrdIDs).
     */
    @GetMapping("/events")
    public ResponseEntity<List<OrderEventDto>> getOrderEventsByClOrdId(
            @RequestParam(required = false) String fixClOrdId,
            @RequestParam(required = false) UUID orderId) {
        
        List<OrderEvent> events;
        
        if (fixClOrdId != null && !fixClOrdId.isBlank()) {
            // Get events by ClOrdID, OrigClOrdID, or fixOrderId
            // This ensures we get all events related to an order, including:
            // - Replace events with temporary ClOrdIDs
            // - Events where ExecutionReport uses OrderID as ClOrdID instead of COPY- prefix
            // Note: We pass fixClOrdId as both clOrdId and orderId params to catch all cases
            events = orderEventRepository.findByClOrdIdOrOrigClOrdIdOrFixOrderIdOrderByEventTimeAsc(fixClOrdId, fixClOrdId);
        } else if (orderId != null) {
            // Get events by order ID
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Order order = orderOpt.get();
            // Prefer querying by fixOrderId if available - it's simpler and more reliable
            // OrderID remains constant even when ClOrdID changes due to replaces
            // For shadow orders, fixOrderId might not be set in order entity, so get it from events first
            String fixOrderId = order.getFixOrderId();
            if ((fixOrderId == null || fixOrderId.isBlank()) && order.getFixClOrdId() != null) {
                // Try to get fixOrderId from the first event (shadow orders might not have it in order entity)
                List<OrderEvent> firstEvents = orderEventRepository.findByClOrdIdOrOrigClOrdIdOrderByEventTimeAsc(order.getFixClOrdId());
                if (!firstEvents.isEmpty() && firstEvents.get(0).getFixOrderId() != null) {
                    fixOrderId = firstEvents.get(0).getFixOrderId();
                    log.debug("Retrieved fixOrderId from event for shadow order: ClOrdID={}, FixOrderId={}", 
                            order.getFixClOrdId(), fixOrderId);
                }
            }
            
            if (fixOrderId != null && !fixOrderId.isBlank()) {
                events = orderEventRepository.findByFixOrderIdOrderByEventTimeAsc(fixOrderId);
            } else if (order.getFixClOrdId() != null) {
                // Fallback to ClOrdID/OrigClOrdID query if fixOrderId is not available
                events = orderEventRepository.findByClOrdIdOrOrigClOrdIdOrFixOrderIdOrderByEventTimeAsc(
                        order.getFixClOrdId(), order.getFixOrderId());
            } else {
                events = orderEventRepository.findByOrderOrderByEventTimeAsc(order);
            }
        } else {
            return ResponseEntity.badRequest().build();
        }
        
        List<OrderEventDto> dtos = events.stream()
                .map(this::eventToDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Convert OrderEvent entity to DTO.
     */
    private OrderEventDto eventToDto(OrderEvent event) {
        return OrderEventDto.builder()
                .id(event.getId())
                .orderId(event.getOrder() != null ? event.getOrder().getId() : null)
                .fixExecId(event.getFixExecId())
                .execType(event.getExecType())
                .ordStatus(event.getOrdStatus())
                .fixOrderId(event.getFixOrderId())
                .fixClOrdId(event.getFixClOrdId())
                .fixOrigClOrdId(event.getFixOrigClOrdId())
                .symbol(event.getSymbol())
                .side(event.getSide())
                .ordType(event.getOrdType())
                .timeInForce(event.getTimeInForce())
                .orderQty(event.getOrderQty())
                .price(event.getPrice())
                .stopPx(event.getStopPx())
                .lastPx(event.getLastPx())
                .lastQty(event.getLastQty())
                .cumQty(event.getCumQty())
                .leavesQty(event.getLeavesQty())
                .avgPx(event.getAvgPx())
                .account(event.getAccount())
                .transactTime(event.getTransactTime())
                .text(event.getText())
                .eventTime(event.getEventTime())
                .rawFixMessage(event.getRawFixMessage())
                .sessionId(event.getSessionId())
                .build();
    }

    /**
     * Convert Order entity to DTO.
     * Uses the latest event to get the current order status and execution details.
     */
    private OrderDto toDto(Order order) {
        Account account = order.getAccount();
        
        // Get the latest event for this order to get the current status
        // Events have the final order status from ExecutionReports
        // Prefer querying by fixOrderId - it's simpler and OrderID remains constant even when ClOrdID changes
        // For shadow orders, fixOrderId might not be set in order entity, so get it from events first
        OrderEvent latestEvent = null;
        String fixOrderId = order.getFixOrderId();
        if ((fixOrderId == null || fixOrderId.isBlank()) && order.getFixClOrdId() != null) {
            // Try to get fixOrderId from the first event (shadow orders might not have it in order entity)
            List<OrderEvent> firstEvents = orderEventRepository.findByClOrdIdOrOrigClOrdIdOrderByEventTimeAsc(order.getFixClOrdId());
            if (!firstEvents.isEmpty() && firstEvents.get(0).getFixOrderId() != null) {
                fixOrderId = firstEvents.get(0).getFixOrderId();
            }
        }
        
        if (fixOrderId != null && !fixOrderId.isBlank()) {
            // Query by fixOrderId - simplest and most reliable for shadow account orders
            List<OrderEvent> events = orderEventRepository.findByFixOrderIdOrderByEventTimeDesc(fixOrderId);
            if (!events.isEmpty()) {
                latestEvent = events.get(0);
            }
        } else if (order.getFixClOrdId() != null) {
            // Fallback to ClOrdID/OrigClOrdID query if fixOrderId is not available
            List<OrderEvent> events = orderEventRepository.findByClOrdIdOrOrigClOrdIdOrFixOrderIdOrderByEventTimeDesc(
                    order.getFixClOrdId(), order.getFixOrderId());
            if (!events.isEmpty()) {
                latestEvent = events.get(0);
            }
        }
        
        // Use status fields from latest event if available, otherwise fall back to order entity
        Character ordStatus = latestEvent != null ? latestEvent.getOrdStatus() : order.getOrdStatus();
        Character execType = latestEvent != null ? latestEvent.getExecType() : order.getExecType();
        BigDecimal cumQty = latestEvent != null ? latestEvent.getCumQty() : order.getCumQty();
        BigDecimal leavesQty = latestEvent != null ? latestEvent.getLeavesQty() : order.getLeavesQty();
        BigDecimal avgPx = latestEvent != null ? latestEvent.getAvgPx() : order.getAvgPx();
        BigDecimal lastPx = latestEvent != null ? latestEvent.getLastPx() : order.getLastPx();
        BigDecimal lastQty = latestEvent != null ? latestEvent.getLastQty() : order.getLastQty();
        
        // Use fixOrderId from latest event if available (more up-to-date)
        // Reuse the fixOrderId we already retrieved above, or get from latest event
        String finalFixOrderId = fixOrderId != null && !fixOrderId.isBlank() 
                ? fixOrderId 
                : (latestEvent != null && latestEvent.getFixOrderId() != null 
                        ? latestEvent.getFixOrderId() 
                        : order.getFixOrderId());
        
        // Count events - use the fixOrderId we already retrieved above
        int eventCount = 0;
        if (fixOrderId != null && !fixOrderId.isBlank()) {
            List<OrderEvent> allEvents = orderEventRepository.findByFixOrderIdOrderByEventTimeAsc(fixOrderId);
            eventCount = allEvents.size();
        } else if (order.getFixClOrdId() != null) {
            List<OrderEvent> allEvents = orderEventRepository.findByClOrdIdOrOrigClOrdIdOrFixOrderIdOrderByEventTimeAsc(
                    order.getFixClOrdId(), order.getFixOrderId());
            eventCount = allEvents.size();
        }
        
        return OrderDto.builder()
                .id(order.getId())
                .accountId(account != null ? account.getId() : null)
                .accountNumber(account != null ? account.getAccountNumber() : null)
                .accountType(account != null ? account.getAccountType() : null)
                .primaryOrderClOrdId(order.getPrimaryOrderClOrdId())
                .fixOrderId(finalFixOrderId)
                .fixClOrdId(order.getFixClOrdId())
                .fixOrigClOrdId(order.getFixOrigClOrdId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .ordType(order.getOrdType())
                .timeInForce(order.getTimeInForce())
                .orderQty(order.getOrderQty())
                .price(order.getPrice())
                .stopPx(order.getStopPx())
                .exDestination(order.getExDestination())
                .execType(execType)
                .ordStatus(ordStatus)
                .cumQty(cumQty)
                .leavesQty(leavesQty)
                .avgPx(avgPx)
                .lastPx(lastPx)
                .lastQty(lastQty)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .isCopyOrder(order.getFixClOrdId() != null && order.getFixClOrdId().startsWith("COPY-"))
                .isLocateOrder(isLocateOrder(order))
                .eventCount(eventCount)
                .build();
    }
    
    /**
     * Check if an order is a locate order.
     * A locate order is identified by:
     * - Side=BUY (1)
     * - AND ClOrdID starts with "LOC-" (primary locate orders)
     * - OR ClOrdID starts with "COPY-" AND ExDestination is a locate route (shadow locate orders)
     * 
     * Note: We don't rely solely on ExDestination being a locate route because regular BUY orders
     * might also use routes that have routeType set, which would incorrectly mark them as locate orders.
     */
    private boolean isLocateOrder(Order order) {
        // Check if Side is BUY
        if (order.getSide() == null || order.getSide() != '1') { // '1' = BUY
            return false;
        }
        
        String clOrdId = order.getFixClOrdId();
        if (clOrdId == null) {
            return false;
        }
        
        // Primary locate orders: ClOrdID starts with "LOC-"
        if (clOrdId.startsWith("LOC-")) {
            return true;
        }
        
        // Shadow locate orders: ClOrdID starts with "COPY-LOC-" AND ExDestination is a locate route
        if (clOrdId.startsWith("COPY")) {
            String exDestination = order.getExDestination();
            if (exDestination != null && !exDestination.isBlank()) {
                if (routeService.isLocateRoute(exDestination)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}

