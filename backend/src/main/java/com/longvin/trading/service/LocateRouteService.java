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


@Service
public class LocateRouteService {

    private static final Logger log = LoggerFactory.getLogger(LocateRouteService.class);


    private final Map<String, List<String>> symbolToLocateRoutes = new ConcurrentHashMap<>();


    private final Map<String, String> primaryToAlternativeRoute = new ConcurrentHashMap<>();


    @PostConstruct
    public void initializeRoutes() {
        // 初始化股票做空Locate路由配置
        // 根据券商实际提供的Locate路由名称配置（请联系券商/DAS获取实际路由名）
        symbolToLocateRoutes.put("AAPL", Arrays.asList("LOCATE_A", "LOCATE_B", "LOCATE_C"));
        symbolToLocateRoutes.put("MSFT", Arrays.asList("LOCATE_X", "LOCATE_Y", "LOCATE_Z"));
        symbolToLocateRoutes.put("GOOGL", Arrays.asList("LOCATE_P", "LOCATE_Q", "LOCATE_R"));
        symbolToLocateRoutes.put("TSLA", Arrays.asList("LOCATE_A", "LOCATE_B"));
        symbolToLocateRoutes.put("NVDA", Arrays.asList("LOCATE_X", "LOCATE_Y"));

        // 普通订单路由的备选路由配置
        primaryToAlternativeRoute.put("PRIMARY_ROUTE", "ALTERNATIVE_ROUTE");
        primaryToAlternativeRoute.put("SMART", "NYSE");
        primaryToAlternativeRoute.put("NASDAQ", "ARCA");
        
        log.info("Initialized locate routes for {} symbols and {} primary-alternative route mappings", 
            symbolToLocateRoutes.size(), primaryToAlternativeRoute.size());
    }


    public String getAvailableLocateRoute(String symbol) {
        List<String> routes = symbolToLocateRoutes.get(symbol.toUpperCase());
        if (routes != null && !routes.isEmpty()) {
            int index = Math.abs(symbol.hashCode()) % routes.size();
            return routes.get(index);
        }


        return getDefaultLocateRoute();
    }


    /**
     * 获取默认Locate路由
     * 注意：实际部署时需要将此值替换为券商提供的真实Locate路由名称
     */
    public String getDefaultLocateRoute() {
        // TODO: 联系券商/DAS获取实际的默认Locate路由名称
        return "DEFAULT_LOCATE";
    }


    public String getAlternativeRoute(String primaryRoute) {
        return primaryToAlternativeRoute.getOrDefault(primaryRoute, null);
    }


    public void addLocateRoute(String symbol, String route) {
        symbolToLocateRoutes.computeIfAbsent(symbol.toUpperCase(), k -> new ArrayList<>()).add(route);
        log.info("Added locate route: {} for symbol: {}", route, symbol);
    }

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

    public List<String> getAllLocateRoutes(String symbol) {
        List<String> routes = symbolToLocateRoutes.get(symbol.toUpperCase());
        return routes != null ? new ArrayList<>(routes) : new ArrayList<>();
    }
}
