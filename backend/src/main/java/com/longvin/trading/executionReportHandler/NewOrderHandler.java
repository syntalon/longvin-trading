package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.fixSender.FixMessageSender;
import com.longvin.trading.service.AccountCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handler for new order ExecutionReports (ExecType=0, OrdStatus=0).
 * 
 * When a new order is confirmed:
 * - For stop limit orders: Copies the order to shadow accounts immediately
 * - For other orders: Logs the confirmation. Order replication is handled by FillOrderHandler when orders are filled.
 */
@Component
public class NewOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(NewOrderHandler.class);
    
    private final AccountCacheService accountCacheService;
    private final FixSessionRegistry fixSessionRegistry;
    private final FixMessageSender fixMessageSender;
    
    public NewOrderHandler(AccountCacheService accountCacheService,
                          FixSessionRegistry fixSessionRegistry,
                          FixMessageSender fixMessageSender) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.fixMessageSender = fixMessageSender;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '0' && context.getOrdStatus() == '0';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        log.info("New order confirmed. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}",
                context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty());
        
        // Skip if it's a copy order
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.debug("Skipping order copy in new order handler - this is already a copy order. ClOrdID={}", context.getClOrdID());
            return;
        }
        
        // Check if this is a stop order (stop market OrdType='3' or stop limit OrdType='4') - if so, copy to shadow accounts
        if (context.getOrdType() != null && (context.getOrdType() == '3' || context.getOrdType() == '4')) {
            // Get account to check if it's a primary account
            if (context.getAccount() != null) {
                Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
                if (accountOpt.isPresent() && accountOpt.get().getAccountType() == AccountType.PRIMARY) {
                    String ordTypeName = context.getOrdType() == '3' ? "STOP(3)" : "STOP_LIMIT(4)";
                    log.info("Stop order confirmed - copying to shadow accounts. ClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}, OrdType={}",
                            context.getClOrdID(), context.getOrderID(), context.getSymbol(),
                            context.getSide(), context.getOrderQty(), ordTypeName);
                    copyStopOrderToShadowAccounts(context);
                } else {
                    log.debug("Skipping stop order copy - not a primary account. ClOrdID={}, Account={}", 
                            context.getClOrdID(), context.getAccount());
                }
            } else {
                log.warn("Cannot copy stop order - Account field missing in ExecutionReport. ClOrdID={}", 
                        context.getClOrdID());
            }
        }
    }
    
    /**
     * Copy stop order (stop market or stop limit) to shadow accounts.
     */
    private void copyStopOrderToShadowAccounts(ExecutionReportContext context) {
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy stop order to shadow accounts. ClOrdID={}, Account: {}",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Get primary account to filter shadow accounts by strategy_key
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot copy stop order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> primaryAccountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (primaryAccountOpt.isEmpty()) {
            log.warn("Primary account not found for account number: {}, cannot copy stop order. ClOrdID={}", 
                    context.getAccount(), context.getClOrdID());
            return;
        }
        
        Account primaryAccount = primaryAccountOpt.get();
        
        // Get shadow accounts with same strategy_key as primary account
        List<Account> shadowAccounts = accountCacheService.findActiveShadowAccountsByStrategyKey(primaryAccount);
        String ordTypeName = context.getOrdType() == '3' ? "STOP(3)" : "STOP_LIMIT(4)";
        log.info("Found {} shadow account(s) for stop order copy: StrategyKey={}, PrimaryAccount={}, PrimaryAccountId={}, OrdType={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getStrategyKey(), primaryAccount.getAccountNumber(), primaryAccount.getId(),
                ordTypeName, shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found with same strategy_key ({}), cannot copy stop order. ClOrdID={}, AccountId: {}",
                    primaryAccount.getStrategyKey(), context.getClOrdID(), primaryAccount.getId());
            return;
        }
        
        // Send stop order to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                sendStopOrderToShadowAccount(context, shadowAccount, primaryAccount, initiatorSessionID);
            } catch (Exception e) {
                log.error("Error copying stop order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Send stop order (stop market or stop limit) to a shadow account.
     */
    private void sendStopOrderToShadowAccount(ExecutionReportContext context, Account shadowAccount, 
                                              Account primaryAccount, SessionID initiatorSessionID) {
        String shadowAccountNumber = shadowAccount.getAccountNumber();
        String clOrdId = "COPY-" + shadowAccountNumber + "-" + context.getClOrdID();
        
        // Build order parameters
        Map<String, Object> orderParams = new HashMap<>();
        orderParams.put("clOrdID", clOrdId);
        orderParams.put("side", context.getSide());
        orderParams.put("symbol", context.getSymbol());
        orderParams.put("orderQty", context.getOrderQty().intValue());
        orderParams.put("account", shadowAccountNumber);
        orderParams.put("ordType", context.getOrdType()); // '3' for STOP, '4' for STOP_LIMIT
        
        // TimeInForce - default to DAY if not available in context
        // Note: TimeInForce is typically not in ExecutionReport, so we default to DAY
        orderParams.put("timeInForce", '0'); // DAY
        
        // Set price for stop limit orders (OrdType='4')
        if (context.getOrdType() == '4' && context.getPrice() != null) {
            orderParams.put("price", context.getPrice().doubleValue());
        }
        
        // Set stop price for stop orders (required for both STOP and STOP_LIMIT orders)
        if (context.getStopPx() != null) {
            orderParams.put("stopPx", context.getStopPx().doubleValue());
        }
        
        // Use the same route (exDestination) as the primary account order
        String route = context.getExDestination();
        if (route != null && !route.isBlank()) {
            orderParams.put("exDestination", route);
        }
        
        Long primaryAccountId = primaryAccount.getId();
        String routeInfo = route != null ? ", Route=" + route : "";
        String ordTypeName = context.getOrdType() == '3' ? "STOP(3)" : "STOP_LIMIT(4)";
        log.info("Sending stop order copy to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, Side={}, Qty={}, OrdType={}, Price={}, StopPx={}{}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId, context.getSymbol(),
                context.getSide(), context.getOrderQty(), ordTypeName, context.getPrice(), context.getStopPx(), routeInfo);
        
        // Send order
        fixMessageSender.sendNewOrderSingle(initiatorSessionID, orderParams);
        
        log.info("Stop order copy sent to shadow account {} (AccountId: {}): ClOrdID={}, PrimaryAccountId: {}, Symbol={}, Side={}, Qty={}, OrdType={}{}",
                shadowAccountNumber, shadowAccount.getId(), clOrdId, primaryAccountId,
                context.getSymbol(), context.getSide(), context.getOrderQty(), ordTypeName, routeInfo);
    }
}