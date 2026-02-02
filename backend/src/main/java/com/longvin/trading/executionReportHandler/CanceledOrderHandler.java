package com.longvin.trading.executionReportHandler;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import com.longvin.trading.entities.accounts.Account;
import com.longvin.trading.entities.accounts.AccountType;
import com.longvin.trading.fix.FixSessionRegistry;
import com.longvin.trading.service.AccountCacheService;
import com.longvin.trading.service.CopyRuleService;
import com.longvin.trading.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handler for canceled order ExecutionReports (ExecType=4, OrdStatus=4).
 * 
 * When a primary account order is canceled, this handler copies the cancel to shadow accounts.
 */
@Component
public class CanceledOrderHandler implements ExecutionReportHandler {
    
    private static final Logger log = LoggerFactory.getLogger(CanceledOrderHandler.class);
    
    private final AccountCacheService accountCacheService;
    private final FixSessionRegistry fixSessionRegistry;
    private final CopyRuleService copyRuleService;
    private final OrderService orderService;
    
    public CanceledOrderHandler(AccountCacheService accountCacheService,
                               FixSessionRegistry fixSessionRegistry,
                               CopyRuleService copyRuleService,
                               OrderService orderService) {
        this.accountCacheService = accountCacheService;
        this.fixSessionRegistry = fixSessionRegistry;
        this.copyRuleService = copyRuleService;
        this.orderService = orderService;
    }

    @Override
    public boolean supports(ExecutionReportContext context) {
        return context.getExecType() == '4' && context.getOrdStatus() == '4';
    }

    @Override
    public void handle(ExecutionReportContext context, SessionID sessionID) {
        // Log canceled order details
        String origClOrdID = context.getOrigClOrdID() != null ? context.getOrigClOrdID() : context.getClOrdID();
        log.info("Order canceled. ClOrdID={}, OrigClOrdID={}, OrderID={}, Symbol={}, Side={}, Qty={}, Account={}",
                context.getClOrdID(), origClOrdID, context.getOrderID(), context.getSymbol(),
                context.getSide(), context.getOrderQty(), context.getAccount());
        
        // Handle copy orders (shadow account orders) - create event, don't replicate
        if (context.getClOrdID() != null && context.getClOrdID().startsWith("COPY-")) {
            log.info("Copy order canceled - creating event. ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
            try {
                // Create event for the shadow order cancel
                orderService.createEventForShadowOrder(context, sessionID);
            } catch (Exception e) {
                log.error("Error creating event for shadow order cancel: ClOrdID={}, Error={}",
                        context.getClOrdID(), e.getMessage(), e);
            }
            return;
        }
        
        // Check if this is a primary account order
        if (context.getAccount() == null) {
            log.warn("Account field missing in ExecutionReport, cannot copy cancel order. ClOrdID={}", 
                    context.getClOrdID());
            return;
        }
        
        Optional<Account> accountOpt = accountCacheService.findByAccountNumber(context.getAccount());
        if (accountOpt.isEmpty() || accountOpt.get().getAccountType() != AccountType.PRIMARY) {
            log.debug("Skipping cancel order copy - not a primary account. ClOrdID={}, Account={}",
                    context.getClOrdID(), context.getAccount());
            return;
        }
        
        Account primaryAccount = accountOpt.get();
        
        // Create event for primary account order cancel
        try {
            orderService.createEventForOrder(context, sessionID);
            log.debug("Created event for primary order cancel: ClOrdID={}, OrigClOrdID={}",
                    context.getClOrdID(), origClOrdID);
        } catch (Exception e) {
            log.error("Error creating event for primary order cancel: ClOrdID={}, Error={}",
                    context.getClOrdID(), e.getMessage(), e);
        }
        
        // Get shadow accounts that match copy rules (same as ReplacedOrderHandler and NewOrderHandler)
        Character ordType = context.getOrdType() != null ? context.getOrdType() : '1'; // Default to MARKET
        String symbol = context.getSymbol();
        BigDecimal quantity = context.getOrderQty();
        
        List<Account> shadowAccounts = copyRuleService.getShadowAccountsForCopy(
                primaryAccount, ordType, symbol, quantity);
        
        log.info("Found {} shadow account(s) for cancel order via copy rules: PrimaryAccount={}, PrimaryAccountId={}, OrdType={}, Symbol={}, Qty={}, ShadowAccounts={}",
                shadowAccounts.size(), primaryAccount.getAccountNumber(), primaryAccount.getId(), ordType, symbol, quantity,
                shadowAccounts.stream().map(Account::getAccountNumber).collect(Collectors.toList()));
        
        if (shadowAccounts.isEmpty()) {
            log.warn("No shadow accounts found matching copy rules, cannot copy cancel order. ClOrdID={}, AccountId: {}, OrdType={}, Symbol={}, Qty={}",
                    context.getClOrdID(), primaryAccount.getId(), ordType, symbol, quantity);
            return;
        }
        
        // Get initiator session for sending orders
        Optional<SessionID> initiatorSessionOpt = fixSessionRegistry.findAnyLoggedOnInitiator();
        if (initiatorSessionOpt.isEmpty()) {
            log.warn("No logged-on initiator session found, cannot copy cancel order to shadow accounts. ClOrdID={}",
                    context.getClOrdID());
            return;
        }
        SessionID initiatorSessionID = initiatorSessionOpt.get();
        
        // Determine primary ClOrdID to use for constructing shadow ClOrdID
        // Use origClOrdID if available, otherwise use current ClOrdID
        String primaryClOrdId = origClOrdID;
        
        // Send cancel to each shadow account
        for (Account shadowAccount : shadowAccounts) {
            try {
                // Construct shadow ClOrdID: COPY-{shadowAccount}-{primaryClOrdID}
                String shadowClOrdId = "COPY-" + shadowAccount.getAccountNumber() + "-" + primaryClOrdId;
                sendCancelOrderToShadowAccount(shadowClOrdId, shadowAccount.getAccountNumber(), initiatorSessionID);
            } catch (Exception e) {
                log.error("Error sending cancel order to shadow account {} (AccountId: {}): {}", 
                        shadowAccount.getAccountNumber(), shadowAccount.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Send cancel order request to a shadow account.
     */
    private void sendCancelOrderToShadowAccount(String shadowClOrdId, String shadowAccountNumber, 
                                               SessionID initiatorSessionID) {
        Message cancelRequest = new Message();
        quickfix.Message.Header header = cancelRequest.getHeader();

        header.setString(MsgType.FIELD, MsgType.ORDER_CANCEL_REQUEST);
        header.setString(SenderCompID.FIELD, initiatorSessionID.getSenderCompID());
        header.setString(TargetCompID.FIELD, initiatorSessionID.getTargetCompID());

        cancelRequest.setString(OrigClOrdID.FIELD, shadowClOrdId);
        cancelRequest.setString(ClOrdID.FIELD, shadowClOrdId); // Use same ClOrdID for cancel

        try {
            Session.sendToTarget(cancelRequest, initiatorSessionID);
            log.info("Order Cancel Request sent to shadow account: ClOrdID={}, ShadowAccount={}",
                    shadowClOrdId, shadowAccountNumber);
        } catch (SessionNotFound e) {
            log.error("Failed to send Order Cancel Request to shadow account: ClOrdID={}, ShadowAccount={}",
                    shadowClOrdId, shadowAccountNumber, e);
        }
    }
}

