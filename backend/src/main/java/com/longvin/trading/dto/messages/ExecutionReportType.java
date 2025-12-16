package com.longvin.trading.dto.messages;


public enum ExecutionReportType {

    ORDER_NEW('0', '0'),
    ORDER_PARTIALLY_FILLED('1', '1'),
    ORDER_FILLED('2', '2'),


    ORDER_CANCELLED('4', '4'),
    ORDER_PENDING_CANCEL('6', '6'),
    ORDER_REPLACED('5', '5'),


    ORDER_REJECTED('8', '8'),


    LOCATE_OFFER_RECEIVED('B', 'B'),
    ORDER_CALCULATED('8', 'B'),


    ORDER_DONE_FOR_DAY('3', '3'),
    ORDER_STOPPED('7', '7'),
    ORDER_SUSPENDED('9', '9');

    private final char execType;
    private final char ordStatus;

    ExecutionReportType(char execType, char ordStatus) {
        this.execType = execType;
        this.ordStatus = ordStatus;
    }

    public static ExecutionReportType fromContext(ExecutionReportContext ctx) {
        for (ExecutionReportType type : values()) {
            if (type.execType == ctx.getExecType() && type.ordStatus == ctx.getOrdStatus()) {
                return type;
            }
        }


        if (ctx.getOrdStatus() == 'B') {
            return LOCATE_OFFER_RECEIVED;
        }


        return ORDER_NEW;
    }
}