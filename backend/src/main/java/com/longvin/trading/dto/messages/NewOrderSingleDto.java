package com.longvin.trading.dto.messages;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for FIX NewOrderSingle message (MsgType=D).
 * Represents a new order submission.
 */
public class NewOrderSingleDto {

    private String clOrdId; // ClOrdID (11) - client order ID
    private HandlInst handlInst; // HandlInst (21) - handling instructions
    private String symbol; // Symbol (55)
    private Side side; // Side (54)
    private OrdType ordType; // OrdType (40)
    private BigDecimal orderQty; // OrderQty (38)
    private BigDecimal price; // Price (44) - required for LIMIT/STOP_LIMIT
    private BigDecimal stopPx; // StopPx (99) - required for STOP/STOP_LIMIT
    private TimeInForce timeInForce; // TimeInForce (59)
    private String account; // Account (1) - optional account override
    private LocalDateTime transactTime; // TransactTime (60)
    private String text; // Text (58) - optional text message

    public enum HandlInst {
        AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION('1'),
        AUTOMATED_EXECUTION_ORDER_PUBLIC_BROKER_INTERVENTION_OK('2'),
        MANUAL_ORDER_BEST_EXECUTION('3');

        private final char fixValue;

        HandlInst(char fixValue) {
            this.fixValue = fixValue;
        }

        public char getFixValue() {
            return fixValue;
        }

        public static HandlInst fromFixValue(char fixValue) {
            for (HandlInst inst : values()) {
                if (inst.fixValue == fixValue) {
                    return inst;
                }
            }
            throw new IllegalArgumentException("Unknown HandlInst: " + fixValue);
        }
    }

    public enum Side {
        BUY('1'),
        SELL('2'),
        SELL_SHORT('5'),
        SELL_SHORT_EXEMPT('6');

        private final char fixValue;

        Side(char fixValue) {
            this.fixValue = fixValue;
        }

        public char getFixValue() {
            return fixValue;
        }

        public static Side fromFixValue(char fixValue) {
            for (Side side : values()) {
                if (side.fixValue == fixValue) {
                    return side;
                }
            }
            throw new IllegalArgumentException("Unknown Side: " + fixValue);
        }
    }

    public enum OrdType {
        MARKET('1'),
        LIMIT('2'),
        STOP('3'),
        STOP_LIMIT('4'),
        MARKET_ON_CLOSE('5'),
        WITH_OR_WITHOUT('6'),
        LIMIT_OR_BETTER('7'),
        LIMIT_WITH_OR_WITHOUT('8'),
        ON_BASIS('9'),
        ON_CLOSE('A'),
        LIMIT_ON_CLOSE('B'),
        FOREX_MARKET('C'),
        PREVIOUSLY_QUOTED('D'),
        PREVIOUSLY_INDICATED('E'),
        FOREX_LIMIT('F'),
        FOREX_SWAP('G'),
        FOREX_PREVIOUSLY_QUOTED('H'),
        FUNARI('I'),
        MARKET_IF_TOUCHED('J'),
        MARKET_WITH_LEFT_OVER_AS_LIMIT('K'),
        PREVIOUS_FUND_VALUATION_POINT('L'),
        NEXT_FUND_VALUATION_POINT('M'),
        PEGGED('P');

        private final char fixValue;

        OrdType(char fixValue) {
            this.fixValue = fixValue;
        }

        public char getFixValue() {
            return fixValue;
        }

        public static OrdType fromFixValue(char fixValue) {
            for (OrdType type : values()) {
                if (type.fixValue == fixValue) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown OrdType: " + fixValue);
        }
    }

    public enum TimeInForce {
        DAY('0'),
        GTC('1'),
        OPG('2'),
        IOC('3'),
        FOK('4'),
        GTX('5'),
        GTD('6'),
        AT_THE_CLOSE('7'),
        GOOD_THROUGH_CROSSING('8'),
        AT_CROSSING('9');

        private final char fixValue;

        TimeInForce(char fixValue) {
            this.fixValue = fixValue;
        }

        public char getFixValue() {
            return fixValue;
        }

        public static TimeInForce fromFixValue(char fixValue) {
            for (TimeInForce tif : values()) {
                if (tif.fixValue == fixValue) {
                    return tif;
                }
            }
            throw new IllegalArgumentException("Unknown TimeInForce: " + fixValue);
        }
    }

    // Getters and setters
    public String getClOrdId() {
        return clOrdId;
    }

    public void setClOrdId(String clOrdId) {
        this.clOrdId = clOrdId;
    }

    public HandlInst getHandlInst() {
        return handlInst;
    }

    public void setHandlInst(HandlInst handlInst) {
        this.handlInst = handlInst;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    public OrdType getOrdType() {
        return ordType;
    }

    public void setOrdType(OrdType ordType) {
        this.ordType = ordType;
    }

    public BigDecimal getOrderQty() {
        return orderQty;
    }

    public void setOrderQty(BigDecimal orderQty) {
        this.orderQty = orderQty;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getStopPx() {
        return stopPx;
    }

    public void setStopPx(BigDecimal stopPx) {
        this.stopPx = stopPx;
    }

    public TimeInForce getTimeInForce() {
        return timeInForce;
    }

    public void setTimeInForce(TimeInForce timeInForce) {
        this.timeInForce = timeInForce;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public LocalDateTime getTransactTime() {
        return transactTime;
    }

    public void setTransactTime(LocalDateTime transactTime) {
        this.transactTime = transactTime;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}

