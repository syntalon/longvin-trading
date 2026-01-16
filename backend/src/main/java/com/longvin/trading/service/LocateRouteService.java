package com.longvin.trading.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Service for managing locate routes organized by broker.
 * 
 * Each broker can have multiple locate routes, and each route has a type:
 * - Type 0 (and Type 2, treated as Type 0): Price inquiry first, then locate order fills directly
 * - Type 1: Locate order returns offer (OrdStatus=B), then accept/reject, then fills
 */
@Service
public class LocateRouteService {

    private static final Logger log = LoggerFactory.getLogger(LocateRouteService.class);

    /**
     * Route type enumeration.
     * Type 0/2: Locate order fills directly (treated as normal order)
     * Type 1: Locate order returns offer (OrdStatus=B), requires accept/reject
     */
    public enum LocateRouteType {
        TYPE_0,  // Price inquiry first, then locate order fills directly
        TYPE_1    // Locate order returns offer, then accept/reject, then fills
    }

    /**
     * Represents a locate route with its type.
     */
    public static class LocateRouteInfo {
        private final String route;
        private final LocateRouteType type;

        public LocateRouteInfo(String route, LocateRouteType type) {
            this.route = route;
            this.type = type;
        }

        public String getRoute() {
            return route;
        }

        public LocateRouteType getType() {
            return type;
        }

        @Override
        public String toString() {
            return route + " (" + type + ")";
        }
    }

    /**
     * Map of broker name to their locate routes.
     * Each broker has multiple routes, each with a type.
     * Key: Broker name (e.g., "OPAL", "DAST")
     * Value: Map of route name to route type
     */
    private final Map<String, Map<String, LocateRouteType>> brokerRoutes = new ConcurrentHashMap<>();

    /**
     * Map of route name to broker name (for reverse lookup).
     */
    private final Map<String, String> routeToBroker = new ConcurrentHashMap<>();

    /**
     * Legacy map of locate route name to route type (for backward compatibility).
     * This maintains the old structure while we transition to broker-based organization.
     * Defaults to TYPE_0 if not specified.
     */
    private final Map<String, LocateRouteType> routeTypeMap = new ConcurrentHashMap<>();


    @PostConstruct
    public void initializeRoutes() {
        // Initialize default locate routes organized by broker
        
        // Default broker (or "DEFAULT" broker) routes available for all stocks
        String defaultBroker = "DEFAULT";
        Map<String, LocateRouteType> defaultRoutes = new ConcurrentHashMap<>();
        defaultRoutes.put("LOCATE", LocateRouteType.TYPE_1);  // Type 1 route
        defaultRoutes.put("TESTSL", LocateRouteType.TYPE_0);  // Type 0 route
        brokerRoutes.put(defaultBroker, defaultRoutes);
        
        // Update reverse lookup map
        routeToBroker.put("LOCATE", defaultBroker);
        routeToBroker.put("TESTSL", defaultBroker);
        
        // Update legacy routeTypeMap for backward compatibility
        routeTypeMap.putAll(defaultRoutes);
        
        log.info("Initialized locate routes by broker. Default broker '{}' has {} routes: {}", 
                defaultBroker, defaultRoutes.size(), defaultRoutes.keySet());
    }


    /**
     * Get an available locate route for a symbol from the default broker.
     * 
     * @param symbol The stock symbol
     * @return A locate route name, or default route if none available
     */
    public String getAvailableLocateRoute(String symbol) {
        return getAvailableLocateRoute(symbol, "DEFAULT");
    }

    /**
     * Get an available locate route for a symbol from a specific broker.
     * 
     * @param symbol The stock symbol
     * @param broker The broker name
     * @return A locate route name, or default route if none available
     */
    public String getAvailableLocateRoute(String symbol, String broker) {
        if (broker == null || broker.isBlank()) {
            broker = "DEFAULT";
        }
        
        Map<String, LocateRouteType> routes = brokerRoutes.get(broker.toUpperCase());
        if (routes != null && !routes.isEmpty()) {
            List<String> routeList = new ArrayList<>(routes.keySet());
            int index = Math.abs(symbol.hashCode()) % routeList.size();
            return routeList.get(index);
        }
        
        return getDefaultLocateRoute();
    }

    /**
     * Get the default locate route (TESTSL - Type 0).
     * 
     * @return The default locate route name
     */
    public String getDefaultLocateRoute() {
        return "TESTSL";
    }


    /**
     * Get the route type for a given locate route.
     * Searches across all brokers for the route.
     * Defaults to TYPE_0 if not specified.
     * 
     * @param route The locate route name
     * @return The route type (TYPE_0 or TYPE_1)
     */
    public LocateRouteType getRouteType(String route) {
        if (route == null || route.isBlank()) {
            return LocateRouteType.TYPE_0; // Default
        }
        String upperRoute = route.toUpperCase();
        
        // Search across all brokers
        for (Map<String, LocateRouteType> routes : brokerRoutes.values()) {
            LocateRouteType type = routes.get(upperRoute);
            if (type != null) {
                return type;
            }
        }
        
        // Fallback to legacy map
        return routeTypeMap.getOrDefault(upperRoute, LocateRouteType.TYPE_0);
    }

    /**
     * Get the broker that owns a specific route.
     * 
     * @param route The locate route name
     * @return The broker name, or null if route not found
     */
    public String getBrokerForRoute(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        return routeToBroker.get(route.toUpperCase());
    }

    /**
     * Add a locate route to a broker.
     * 
     * @param broker The broker name
     * @param route The locate route name
     * @param routeType The route type (TYPE_0 or TYPE_1)
     */
    public void addRouteToBroker(String broker, String route, LocateRouteType routeType) {
        if (broker == null || broker.isBlank() || route == null || route.isBlank()) {
            log.warn("Cannot add route: broker or route is null/blank. Broker={}, Route={}", broker, route);
            return;
        }
        
        String upperBroker = broker.toUpperCase();
        String upperRoute = route.toUpperCase();
        
        // Add to broker routes
        brokerRoutes.computeIfAbsent(upperBroker, k -> new ConcurrentHashMap<>())
                .put(upperRoute, routeType);
        
        // Update reverse lookup
        routeToBroker.put(upperRoute, upperBroker);
        
        // Update legacy map for backward compatibility
        routeTypeMap.put(upperRoute, routeType);
        
        log.info("Added locate route {} (Type {}) to broker {}", route, routeType, broker);
    }

    /**
     * Remove a locate route from a broker.
     * 
     * @param broker The broker name
     * @param route The locate route name
     */
    public void removeRouteFromBroker(String broker, String route) {
        if (broker == null || broker.isBlank() || route == null || route.isBlank()) {
            return;
        }
        
        String upperBroker = broker.toUpperCase();
        String upperRoute = route.toUpperCase();
        
        Map<String, LocateRouteType> routes = brokerRoutes.get(upperBroker);
        if (routes != null) {
            routes.remove(upperRoute);
            if (routes.isEmpty()) {
                brokerRoutes.remove(upperBroker);
            }
        }
        
        routeToBroker.remove(upperRoute);
        routeTypeMap.remove(upperRoute);
        
        log.info("Removed locate route {} from broker {}", route, broker);
    }

    /**
     * Get all routes for a specific broker.
     * 
     * @param broker The broker name
     * @return List of route info objects containing route name and type
     */
    public List<LocateRouteInfo> getRoutesForBroker(String broker) {
        if (broker == null || broker.isBlank()) {
            return Collections.emptyList();
        }
        
        Map<String, LocateRouteType> routes = brokerRoutes.get(broker.toUpperCase());
        if (routes == null || routes.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<LocateRouteInfo> routeInfos = new ArrayList<>();
        for (Map.Entry<String, LocateRouteType> entry : routes.entrySet()) {
            routeInfos.add(new LocateRouteInfo(entry.getKey(), entry.getValue()));
        }
        
        return Collections.unmodifiableList(routeInfos);
    }

    /**
     * Get all brokers that have locate routes configured.
     * 
     * @return Set of broker names
     */
    public Set<String> getAllBrokers() {
        return Collections.unmodifiableSet(brokerRoutes.keySet());
    }

    /**
     * Check if a route is a locate route (exists in any broker's routes).
     * 
     * @param route The route name to check
     * @return true if it's a locate route, false otherwise
     */
    public boolean isLocateRoute(String route) {
        if (route == null || route.isBlank()) {
            return false;
        }
        String upperRoute = route.toUpperCase();
        
        // Check if route exists in any broker's routes
        return brokerRoutes.values().stream()
                .anyMatch(routes -> routes.containsKey(upperRoute));
    }

    /**
     * Check if a route is a locate route for a specific broker.
     * 
     * @param broker The broker name
     * @param route The route name to check
     * @return true if it's a locate route for the broker, false otherwise
     */
    public boolean isLocateRouteForBroker(String broker, String route) {
        if (broker == null || broker.isBlank() || route == null || route.isBlank()) {
            return false;
        }
        
        Map<String, LocateRouteType> routes = brokerRoutes.get(broker.toUpperCase());
        return routes != null && routes.containsKey(route.toUpperCase());
    }

    /**
     * Confirm if a route is a locate route and return its type information.
     * This is a convenience method that combines isLocateRoute() and getRouteType().
     * 
     * @param route The route name to check
     * @return Optional containing LocateRouteInfo with route name and type if it's a locate route,
     *         empty Optional if it's not a locate route
     */
    public Optional<LocateRouteInfo> getLocateRouteInfo(String route) {
        if (route == null || route.isBlank()) {
            return Optional.empty();
        }
        
        String upperRoute = route.toUpperCase();
        
        // Search across all brokers for the route
        for (Map.Entry<String, Map<String, LocateRouteType>> brokerEntry : brokerRoutes.entrySet()) {
            Map<String, LocateRouteType> routes = brokerEntry.getValue();
            LocateRouteType type = routes.get(upperRoute);
            if (type != null) {
                return Optional.of(new LocateRouteInfo(upperRoute, type));
            }
        }
        
        // Not found in any broker
        return Optional.empty();
    }

    /**
     * Confirm if a route is a locate route and return its type.
     * This is a convenience method that combines isLocateRoute() and getRouteType().
     * 
     * @param route The route name to check
     * @return Optional containing the route type (TYPE_0 or TYPE_1) if it's a locate route,
     *         empty Optional if it's not a locate route
     */
    public Optional<LocateRouteType> getLocateRouteType(String route) {
        return getLocateRouteInfo(route)
                .map(LocateRouteInfo::getType);
    }
}
