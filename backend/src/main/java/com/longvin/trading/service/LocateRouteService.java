package com.longvin.trading.service;

import com.longvin.trading.fixSender.FixMessageSender;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locate路由服务，用于管理券源路由信息
 */
@Service
public class LocateRouteService {

    private static final Logger log = LoggerFactory.getLogger(LocateRouteService.class);

    // 缓存可用的locate路由
    private final Map<String, List<String>> symbolToLocateRoutes = new ConcurrentHashMap<>();

    // 缓存备用路由映射
    private final Map<String, String> primaryToAlternativeRoute = new ConcurrentHashMap<>();

    // 假设从配置或数据库加载路由信息
    @PostConstruct
    public void initializeRoutes() {
        // 初始化示例数据 - 在实际应用中应该从配置文件或数据库加载
        symbolToLocateRoutes.put("AAPL", Arrays.asList("LOCATE_A", "LOCATE_B", "LOCATE_C"));
        symbolToLocateRoutes.put("MSFT", Arrays.asList("LOCATE_X", "LOCATE_Y", "LOCATE_Z"));
        symbolToLocateRoutes.put("GOOGL", Arrays.asList("LOCATE_P", "LOCATE_Q", "LOCATE_R"));

        // 初始化备用路由映射
        primaryToAlternativeRoute.put("PRIMARY_ROUTE", "ALTERNATIVE_ROUTE");
        primaryToAlternativeRoute.put("SMART", "NYSE");
        primaryToAlternativeRoute.put("NASDAQ", "ARCA");
    }

    /**
     * 获取某个股票的可用locate路由
     */
    public String getAvailableLocateRoute(String symbol) {
        List<String> routes = symbolToLocateRoutes.get(symbol.toUpperCase());
        if (routes != null && !routes.isEmpty()) {
            // 简单轮询策略，实际应用中可能需要更复杂的负载均衡策略
            int index = Math.abs(symbol.hashCode()) % routes.size();
            return routes.get(index);
        }

        // 如果没有为该股票配置特定的locate路由，返回默认路由
        return getDefaultLocateRoute();
    }

    /**
     * 获取默认locate路由
     */
    public String getDefaultLocateRoute() {
        // 返回默认locate路由
        return "DEFAULT_LOCATE";
    }

    /**
     * 获取备用路由
     */
    public String getAlternativeRoute(String primaryRoute) {
        return primaryToAlternativeRoute.getOrDefault(primaryRoute, null);
    }

    /**
     * 添加新的locate路由配置
     */
    public void addLocateRoute(String symbol, String route) {
        symbolToLocateRoutes.computeIfAbsent(symbol.toUpperCase(), k -> new ArrayList<>()).add(route);
        log.info("Added locate route: {} for symbol: {}", route, symbol);
    }

    /**
     * 移除locate路由配置
     */
    public void removeLocateRoute(String symbol, String route) {
        List<String> routes = symbolToLocateRoutes.get(symbol.toUpperCase());
        if (routes != null) {
            routes.remove(route);
            if (routes.isEmpty()) {
                symbolToLocateRoutes.remove(symbol.toUpperCase());
            }
            log.info("Removed locate route: {} for symbol: {}", route, symbol);
        }
    }

    /**
     * 获取所有可用的locate路由
     */
    public List<String> getAllLocateRoutes(String symbol) {
        List<String> routes = symbolToLocateRoutes.get(symbol.toUpperCase());
        return routes != null ? new ArrayList<>(routes) : new ArrayList<>();
    }
}
