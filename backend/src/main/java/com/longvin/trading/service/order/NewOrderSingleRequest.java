package com.longvin.trading.service.order;

import java.math.BigDecimal;

/**
 * Request DTO for placing a DAS FIX NewOrderSingle.
 */
public class NewOrderSingleRequest {

    private String senderCompId; // which FIX session to use (order-entry)
    private String clOrdId;
    private String symbol;
    private Side side;
    private OrdType ordType;
    private BigDecimal quantity;
    private BigDecimal price; // required for LIMIT / STOP_LIMIT, ignored for MARKET
    private TimeInForce timeInForce;
    private String account; // optional override

    // Enums map to FIX values; actual mapping is done in the service
    public enum Side { BUY, SELL, SELL_SHORT, SELL_SHORT_EXEMPT }
    public enum OrdType { MARKET, LIMIT, STOP, STOP_LIMIT }
    public enum TimeInForce { DAY, IOC, GTC, GTX, DAY_PLUS }

    // getters and setters
    // ...existing code...

    public String getSenderCompId() { return senderCompId; }
    public void setSenderCompId(String senderCompId) { this.senderCompId = senderCompId; }

    public String getClOrdId() { return clOrdId; }
    public void setClOrdId(String clOrdId) { this.clOrdId = clOrdId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }

    public OrdType getOrdType() { return ordType; }
    public void setOrdType(OrdType ordType) { this.ordType = ordType; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public TimeInForce getTimeInForce() { return timeInForce; }
    public void setTimeInForce(TimeInForce timeInForce) { this.timeInForce = timeInForce; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
}
