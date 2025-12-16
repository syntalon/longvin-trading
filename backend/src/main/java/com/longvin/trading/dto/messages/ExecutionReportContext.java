package com.longvin.trading.dto.messages;

import lombok.Data;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.*;
import java.time.LocalDateTime;
import java.util.Date;


@Data
public class ExecutionReportContext {
    private String clOrdID;
    private String orderID;
    private String origClOrdID;
    private char execType;
    private char ordStatus;
    private String symbol;
    private char side; // 1=Buy, 2=Sell, 5=Sell short
    private String text;
    private int orderQty;
    private int cumQty;
    private int leavesQty;
    private double avgPx;
    private LocalDateTime transactTime;
    private String account;
    private String exDestination; // 路由信息


    private boolean isShortOrder;
    private boolean isLocateRelated;

    public ExecutionReportContext(Message message) throws FieldNotFound {
        this.clOrdID = message.getString(ClOrdID.FIELD);
        this.orderID = message.getString(OrderID.FIELD);

        if (message.isSetField(OrigClOrdID.FIELD)) {
            this.origClOrdID = message.getString(OrigClOrdID.FIELD);
        }

        this.execType = message.getChar(ExecType.FIELD);
        this.ordStatus = message.getChar(OrdStatus.FIELD);
        this.symbol = message.getString(Symbol.FIELD);
        this.side = message.getChar(Side.FIELD);
        this.isShortOrder = this.side == '5';

        if (message.isSetField(Text.FIELD)) {
            this.text = message.getString(Text.FIELD);
        }

        this.orderQty = message.getInt(OrderQty.FIELD);
        this.cumQty = message.getInt(CumQty.FIELD);
        this.leavesQty = message.getInt(LeavesQty.FIELD);

        if (message.isSetField(AvgPx.FIELD)) {
            this.avgPx = message.getDouble(AvgPx.FIELD);
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

        // 特殊标识
        this.isLocateRelated = this.ordStatus == 'B' ||
                (this.ordStatus == '8' && this.text != null &&
                        this.text.toLowerCase().contains("locate"));
    }
}