package com.longvin.trading.rest;

import com.longvin.trading.entities.orders.LocateRequest;
import com.longvin.trading.repository.LocateRequestRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for monitoring locate requests.
 */
@RestController
@RequestMapping("/api/locate-requests")
public class LocateRequestController {

    private final LocateRequestRepository locateRequestRepository;

    public LocateRequestController(LocateRequestRepository locateRequestRepository) {
        this.locateRequestRepository = locateRequestRepository;
    }

    /**
     * Get all locate requests.
     */
    @GetMapping
    public List<LocateRequest> getAllLocateRequests() {
        return locateRequestRepository.findAll();
    }

    /**
     * Get locate request by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<LocateRequest> getLocateRequest(@PathVariable UUID id) {
        Optional<LocateRequest> locateRequest = locateRequestRepository.findById(id);
        return locateRequest.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get locate requests by status.
     */
    @GetMapping("/status/{status}")
    public List<LocateRequest> getLocateRequestsByStatus(@PathVariable LocateRequest.LocateStatus status) {
        return locateRequestRepository.findAll()
            .stream()
            .filter(lr -> lr.getStatus() == status)
            .toList();
    }

    /**
     * Get pending locate requests (for monitoring).
     */
    @GetMapping("/pending")
    public List<LocateRequest> getPendingLocateRequests() {
        return locateRequestRepository.findAll()
            .stream()
            .filter(lr -> lr.getStatus() == LocateRequest.LocateStatus.PENDING)
            .toList();
    }

    /**
     * Get locate requests for a specific order.
     */
    @GetMapping("/order/{orderId}")
    public List<LocateRequest> getLocateRequestsForOrder(@PathVariable UUID orderId) {
        return locateRequestRepository.findAll()
            .stream()
            .filter(lr -> lr.getOrder() != null && lr.getOrder().getId().equals(orderId))
            .toList();
    }
}

