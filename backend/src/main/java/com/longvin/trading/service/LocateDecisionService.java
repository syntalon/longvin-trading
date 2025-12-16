package com.longvin.trading.service;

import com.longvin.trading.dto.messages.ExecutionReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Locate决策服务，用于决定是否接受locate报价
 */
@Service
public class LocateDecisionService {

    private static final Logger log = LoggerFactory.getLogger(LocateDecisionService.class);

    /**
     * 决定是否接受locate报价
     */
    public boolean shouldAcceptLocateOffer(ExecutionReportContext context) {
        // 基于业务规则决定是否接受locate报价
        // 这里是一个示例实现，实际业务逻辑可能更复杂

        // 1. 检查可用券源数量是否满足需求
        if (context.getLeavesQty() < context.getOrderQty()) {
            log.info("Rejecting locate offer - insufficient quantity. Required: {}, Available: {}",
                    context.getOrderQty(), context.getLeavesQty());
            return false;
        }

        // 2. 检查订单金额阈值
        double estimatedCost = context.getAvgPx() * context.getOrderQty();
        if (estimatedCost > getMaximumAcceptableCost(context)) {
            log.info("Rejecting locate offer - cost too high: {}", estimatedCost);
            return false;
        }

        // 3. 检查账户限制
        if (!isAccountAllowedToBorrow(context.getAccount())) {
            log.info("Rejecting locate offer - account not allowed to borrow: {}", context.getAccount());
            return false;
        }

        // 4. 其他业务规则...

        // 默认接受（在实际应用中，这里可能有更复杂的决策逻辑）
        log.info("Accepting locate offer for OrderID: {}, Quantity: {}",
                context.getOrderID(), context.getOrderQty());
        return true;
    }

    /**
     * 获取特定股票的替代locate路由
     */
    public String getAlternativeLocateRoute(String symbol) {
        // 在实际应用中，这里可以从数据库或配置中获取替代路由
        // 这里只是示例
        Map<String, String> alternativeRoutes = new HashMap<>();
        alternativeRoutes.put("AAPL", "LOCATE_B");
        alternativeRoutes.put("MSFT", "LOCATE_Y");
        alternativeRoutes.put("GOOGL", "LOCATE_Q");

        return alternativeRoutes.get(symbol.toUpperCase());
    }

    /**
     * 获取最大可接受成本（示例方法）
     */
    private double getMaximumAcceptableCost(ExecutionReportContext context) {
        // 示例：基于账户类型或风险偏好设置不同阈值
        return 1000000.0; // 100万美元
    }

    /**
     * 检查账户是否被允许借券（示例方法）
     */
    private boolean isAccountAllowedToBorrow(String account) {
        // 示例：基于账户权限检查
        return true; // 假设所有账户都可以借券
    }
}