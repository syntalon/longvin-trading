package com.longvin.trading.dto.messages;

import lombok.Data;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Context object containing parsed ExecutionReport data.
 * Provides a clean abstraction over the raw FIX message.
 */
@Data
public class ExecutionReportContext {
    
    // FIX Side values for short orders
    private static final char SIDE_SELL_SHORT = '5';
    private static final char SIDE_SELL_SHORT_EXEMPT = '6';
    
    // Core order identifiers
    private String clOrdID;
    private String orderID;
    private String origClOrdID;
    private String quoteReqID;  // Tag 131 - for locate correlation
    
    // Execution status
    private char execType;
    private char ordStatus;
    
    // Order details
    private String symbol;
    private char side;  // 1=Buy, 2=Sell, 5=Sell Short, 6=Sell Short Exempt
    private String text;
    private BigDecimal orderQty;
    private BigDecimal cumQty;
    private BigDecimal leavesQty;
    private BigDecimal avgPx;
    private BigDecimal lastPx;
    private BigDecimal lastQty;
    
    // Locate-specific fields (for OrdStatus=B)
    private BigDecimal offerPx;      // Tag 133 - fee per share
    private BigDecimal offerSize;    // Tag 135 - available shares
    
    // Timing and routing
    private LocalDateTime transactTime;
    private String account;
    private String exDestination;    // Route information
    
    // Derived flags
    private boolean shortOrder;
    private boolean locateRelated;
    private boolean quoteResponse; // New flag for MsgType=S

    /**
     * Parse ExecutionReport message into context.
     */
    public ExecutionReportContext(Message message) throws FieldNotFound {
        String msgType = message.getHeader().getString(MsgType.FIELD);

        if ("S".equals(msgType)) {
            // Handle Short Locate Quote Response (MsgType=S)
            this.quoteResponse = true;
            this.quoteReqID = message.getString(QuoteReqID.FIELD);
            this.symbol = message.getString(Symbol.FIELD);

            if (message.isSetField(OfferPx.FIELD)) {
                this.offerPx = BigDecimal.valueOf(message.getDouble(OfferPx.FIELD));
            }

            if (message.isSetField(OfferSize.FIELD)) {
                this.offerSize = BigDecimal.valueOf(message.getDouble(OfferSize.FIELD));
            }

            if (message.isSetField(Text.FIELD)) {
                this.text = message.getString(Text.FIELD);
            }

            // Map QuoteReqID to ClOrdID for compatibility with handlers that use ClOrdID as key
            this.clOrdID = this.quoteReqID;

            // Set defaults for required ExecutionReport fields to avoid NPEs in getters
            this.orderQty = BigDecimal.ZERO;
            this.cumQty = BigDecimal.ZERO;
            this.leavesQty = BigDecimal.ZERO;
            this.lastQty = BigDecimal.ZERO;
            this.avgPx = BigDecimal.ZERO;
            this.lastPx = BigDecimal.ZERO;

            return;
        }

        // Required fields for ExecutionReport (MsgType=8)
        this.clOrdID = message.getString(ClOrdID.FIELD);
        this.orderID = message.getString(OrderID.FIELD);
        this.execType = message.getChar(ExecType.FIELD);
        this.ordStatus = message.getChar(OrdStatus.FIELD);
        this.symbol = message.getString(Symbol.FIELD);
        this.side = message.getChar(Side.FIELD);
        
        // Quantities
        this.orderQty = BigDecimal.valueOf(message.getDouble(OrderQty.FIELD));
        // CumQty (field 14) is optional - DAS Trader may not always include it
        if (message.isSetField(CumQty.FIELD)) {
            this.cumQty = BigDecimal.valueOf(message.getDouble(CumQty.FIELD));
        } else {
            this.cumQty = BigDecimal.ZERO; // Default to zero if not present
        }
        // LeavesQty (field 151) is optional - DAS Trader may not always include it
        if (message.isSetField(LeavesQty.FIELD)) {
            this.leavesQty = BigDecimal.valueOf(message.getDouble(LeavesQty.FIELD));
        } else {
            // If LeavesQty is missing, calculate it from OrderQty - CumQty
            // If CumQty is also missing (defaults to 0), then LeavesQty = OrderQty
            this.leavesQty = this.orderQty.subtract(this.cumQty);
        }

        // Optional fields
        if (message.isSetField(OrigClOrdID.FIELD)) {
            this.origClOrdID = message.getString(OrigClOrdID.FIELD);
        }
        
        if (message.isSetField(QuoteReqID.FIELD)) {
            this.quoteReqID = message.getString(QuoteReqID.FIELD);
        }

        if (message.isSetField(Text.FIELD)) {
            this.text = message.getString(Text.FIELD);
        }

        if (message.isSetField(AvgPx.FIELD)) {
            this.avgPx = BigDecimal.valueOf(message.getDouble(AvgPx.FIELD));
        }
        
        if (message.isSetField(LastPx.FIELD)) {
            this.lastPx = BigDecimal.valueOf(message.getDouble(LastPx.FIELD));
        }
        
        if (message.isSetField(LastShares.FIELD)) {
            this.lastQty = BigDecimal.valueOf(message.getDouble(LastShares.FIELD));
        }
        
        // Locate-specific fields (Tag 133 OfferPx, Tag 135 OfferSize)
        if (message.isSetField(OfferPx.FIELD)) {
            this.offerPx = BigDecimal.valueOf(message.getDouble(OfferPx.FIELD));
        }
        
        if (message.isSetField(OfferSize.FIELD)) {
            this.offerSize = BigDecimal.valueOf(message.getDouble(OfferSize.FIELD));
        }

        if (message.isSetField(TransactTime.FIELD)) {
            this.transactTime = message.getUtcTimeStamp(TransactTime.FIELD);
        }

        if (message.isSetField(Account.FIELD)) {
            this.account = message.getString(Account.FIELD);
        }

        if (message.isSetField(ExDestination.FIELD)) {
            this.exDestination = message.getString(ExDestination.FIELD);
        }

        // Derived flags
        this.shortOrder = (this.side == SIDE_SELL_SHORT || this.side == SIDE_SELL_SHORT_EXEMPT);
        this.locateRelated = this.ordStatus == 'B' ||
                (this.ordStatus == '8' && this.text != null &&
                        this.text.toLowerCase().contains("locate"));
    }
    
    /**
     * Check if this is a short order (Side=5 or Side=6).
     */
    public boolean isShortOrder() {
        return shortOrder;
    }
    
    /**
     * Check if this is a Quote Response (MsgType=S).
     */
    public boolean isQuoteResponse() {
        return quoteResponse;
    }

    /**
     * Check if this execution report is locate-related.
     */
    public boolean isLocateRelated() {
        return locateRelated;
    }
    
    /**
     * Get order quantity as int (for backward compatibility).
     */
    public int getOrderQtyAsInt() {
        return orderQty != null ? orderQty.intValue() : 0;
    }
}