package com.longvin.trading.rest;

import com.longvin.trading.dto.copy.CopyRuleDto;
import com.longvin.trading.dto.copy.CreateCopyRuleRequest;
import com.longvin.trading.dto.copy.UpdateCopyRuleRequest;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.copy.CopyRule;
import com.longvin.trading.repository.AccountRepository;
import com.longvin.trading.repository.CopyRuleRepository;
import com.longvin.trading.service.CopyRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for CopyRule CRUD operations.
 */
@RestController
@RequestMapping("/api/copy-rules")
public class CopyRuleController {

    private static final Logger log = LoggerFactory.getLogger(CopyRuleController.class);

    private final CopyRuleRepository copyRuleRepository;
    private final AccountRepository accountRepository;
    private final CopyRuleService copyRuleService;

    public CopyRuleController(CopyRuleRepository copyRuleRepository,
                             AccountRepository accountRepository,
                             CopyRuleService copyRuleService) {
        this.copyRuleRepository = copyRuleRepository;
        this.accountRepository = accountRepository;
        this.copyRuleService = copyRuleService;
    }

    /**
     * Get all copy rules.
     */
    @GetMapping
    public ResponseEntity<List<CopyRuleDto>> getAllCopyRules(
            @RequestParam(required = false) Long primaryAccountId,
            @RequestParam(required = false) Long shadowAccountId,
            @RequestParam(required = false) Boolean active) {
        
        List<CopyRule> rules;
        
        if (primaryAccountId != null) {
            rules = copyRuleRepository.findActiveRulesByPrimaryAccountId(primaryAccountId);
        } else if (shadowAccountId != null) {
            rules = copyRuleRepository.findActiveRulesByShadowAccountId(shadowAccountId);
        } else if (active != null && active) {
            rules = copyRuleRepository.findByActiveTrue();
        } else {
            rules = copyRuleRepository.findAll();
        }
        
        List<CopyRuleDto> dtos = rules.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a copy rule by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<CopyRuleDto> getCopyRuleById(@PathVariable Long id) {
        return copyRuleRepository.findById(id)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new copy rule.
     */
    @PostMapping
    public ResponseEntity<CopyRuleDto> createCopyRule(@RequestBody CreateCopyRuleRequest request) {
        // Validate required fields
        if (request.getPrimaryAccountNumber() == null || request.getPrimaryAccountNumber().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getShadowAccountNumber() == null || request.getShadowAccountNumber().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getRatioType() == null || request.getRatioType().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getRatioValue() == null || request.getRatioValue().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().build();
        }
        
        // Convert account numbers to accounts
        Account primaryAccount = accountRepository.findByAccountNumber(request.getPrimaryAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Primary account not found: " + request.getPrimaryAccountNumber()));
        
        Account shadowAccount = accountRepository.findByAccountNumber(request.getShadowAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Shadow account not found: " + request.getShadowAccountNumber()));
        
        // Check if rule already exists for this account pair
        if (copyRuleRepository.existsByAccountPair(primaryAccount.getId(), shadowAccount.getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .build();
        }
        
        // Build CopyRule entity
        CopyRule rule = CopyRule.builder()
                .primaryAccount(primaryAccount)
                .shadowAccount(shadowAccount)
                .ratioType(CopyRule.CopyRatioType.valueOf(request.getRatioType()))
                .ratioValue(request.getRatioValue())
                .orderTypes(request.getOrderTypes())
                .copyRoute(request.getCopyRoute())
                .locateRoute(request.getLocateRoute())
                .copyBroker(request.getCopyBroker())
                .minQuantity(request.getMinQuantity())
                .maxQuantity(request.getMaxQuantity())
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .active(request.getActive() != null ? request.getActive() : true)
                .description(request.getDescription())
                .config(request.getConfig())
                .build();
        
        CopyRule saved = copyRuleRepository.save(rule);
        
        // Refresh cache
        copyRuleService.refreshCache();
        
        log.info("Created copy rule: id={}, primaryAccount={}, shadowAccount={}", 
                saved.getId(), primaryAccount.getAccountNumber(), shadowAccount.getAccountNumber());
        
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    /**
     * Update an existing copy rule.
     */
    @PutMapping("/{id}")
    public ResponseEntity<CopyRuleDto> updateCopyRule(@PathVariable Long id, 
                                                      @RequestBody UpdateCopyRuleRequest request) {
        CopyRule rule = copyRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Copy rule not found: " + id));
        
        // Update fields if provided
        if (request.getRatioType() != null) {
            rule.setRatioType(CopyRule.CopyRatioType.valueOf(request.getRatioType()));
        }
        if (request.getRatioValue() != null) {
            rule.setRatioValue(request.getRatioValue());
        }
        if (request.getOrderTypes() != null) {
            rule.setOrderTypes(request.getOrderTypes());
        }
        if (request.getCopyRoute() != null) {
            rule.setCopyRoute(request.getCopyRoute());
        }
        if (request.getLocateRoute() != null) {
            rule.setLocateRoute(request.getLocateRoute());
        }
        if (request.getCopyBroker() != null) {
            rule.setCopyBroker(request.getCopyBroker());
        }
        if (request.getMinQuantity() != null) {
            rule.setMinQuantity(request.getMinQuantity());
        }
        if (request.getMaxQuantity() != null) {
            rule.setMaxQuantity(request.getMaxQuantity());
        }
        if (request.getPriority() != null) {
            rule.setPriority(request.getPriority());
        }
        if (request.getActive() != null) {
            rule.setActive(request.getActive());
        }
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription());
        }
        if (request.getConfig() != null) {
            rule.setConfig(request.getConfig());
        }
        
        CopyRule saved = copyRuleRepository.save(rule);
        
        // Refresh cache
        copyRuleService.refreshCache();
        
        log.info("Updated copy rule: id={}", saved.getId());
        
        return ResponseEntity.ok(toDto(saved));
    }

    /**
     * Delete a copy rule.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCopyRule(@PathVariable Long id) {
        if (!copyRuleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        
        copyRuleRepository.deleteById(id);
        
        // Refresh cache
        copyRuleService.refreshCache();
        
        log.info("Deleted copy rule: id={}", id);
        
        return ResponseEntity.noContent().build();
    }

    /**
     * Convert CopyRule entity to DTO.
     */
    private CopyRuleDto toDto(CopyRule rule) {
        return CopyRuleDto.builder()
                .id(rule.getId())
                .primaryAccountId(rule.getPrimaryAccount().getId())
                .primaryAccountNumber(rule.getPrimaryAccount().getAccountNumber())
                .shadowAccountId(rule.getShadowAccount().getId())
                .shadowAccountNumber(rule.getShadowAccount().getAccountNumber())
                .ratioType(rule.getRatioType().name())
                .ratioValue(rule.getRatioValue())
                .orderTypes(rule.getOrderTypes())
                .copyRoute(rule.getCopyRoute())
                .locateRoute(rule.getLocateRoute())
                .copyBroker(rule.getCopyBroker())
                .minQuantity(rule.getMinQuantity())
                .maxQuantity(rule.getMaxQuantity())
                .priority(rule.getPriority())
                .active(rule.getActive())
                .description(rule.getDescription())
                .config(rule.getConfig())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }
}

