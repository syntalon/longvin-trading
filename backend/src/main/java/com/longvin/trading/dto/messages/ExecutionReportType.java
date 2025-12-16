package com.longvin.trading.dto.messages;

/**
 * Execution Report 类型分类
 */
public enum ExecutionReportType {
    // 订单新建
    ORDER_NEW('0', '0'),
    ORDER_PARTIALLY_FILLED('1', '1'),
    ORDER_FILLED('2', '2'),

    // 订单取消相关
    ORDER_CANCELLED('4', '4'),
    ORDER_PENDING_CANCEL('6', '6'),
    ORDER_REPLACED('5', '5'),

    // 订单拒绝
    ORDER_REJECTED('8', '8'),

    // 特殊做空相关
    LOCATE_OFFER_RECEIVED('B', 'B'), // DAS特有：Locate报价
    ORDER_CALCULATED('8', 'B'),      // 已计算，等待Locate确认

    // 其他
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

        // 特殊处理：如果OrdStatus是'B'，可能是Locate相关
        if (ctx.getOrdStatus() == 'B') {
            return LOCATE_OFFER_RECEIVED;
        }

        // 默认返回
        return ORDER_NEW; // 或其他默认值
    }
}