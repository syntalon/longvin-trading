package com.longvin.trading.service;

import com.longvin.trading.entities.accounts.Route;
import com.longvin.trading.repository.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Service for querying route metadata from the database.
 * 
 * This service provides simple queries about routes:
 * - Check if a route is a locate route (has routeType set)
 * - Get route type (TYPE_0 or TYPE_1)
 * - Get available routes for a symbol
 * 
 * Route selection for copying is handled by CopyRuleService.
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    
    private final RouteRepository routeRepository;
    
    // Cache: route name -> Route entity (for fast lookups)
    private final Map<String, Route> routeCache = new ConcurrentHashMap<>();
    
    private volatile boolean initialized = false;

    public RouteService(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    /**
     * Load routes into memory cache.
     */
    @PostConstruct
    @Transactional(readOnly = true)
    public void loadRoutes() {
        log.info("Loading routes into memory cache...");
        
        routeCache.clear();
        
        List<Route> allRoutes = routeRepository.findByActiveTrue();
        
        for (Route route : allRoutes) {
            routeCache.put(route.getName().toUpperCase(), route);
        }
        
        initialized = true;
        log.info("Loaded {} routes into memory cache", allRoutes.size());
    }

    /**
     * Check if a route is a locate route (has routeType set).
     * 
     * @param routeName The route name to check
     * @return true if route exists and has routeType set, false otherwise
     */
    public boolean isLocateRoute(String routeName) {
        ensureInitialized();
        
        if (routeName == null || routeName.isBlank()) {
            return false;
        }
        
        Route route = routeCache.get(routeName.toUpperCase());
        return route != null && route.getRouteType() != null;
    }

    /**
     * Get route type for a locate route.
     * 
     * @param routeName The route name
     * @return Optional containing route type if route exists and is a locate route, empty otherwise
     */
    public Optional<Route.LocateRouteType> getRouteType(String routeName) {
        ensureInitialized();
        
        if (routeName == null || routeName.isBlank()) {
            return Optional.empty();
        }
        
        Route route = routeCache.get(routeName.toUpperCase());
        if (route != null && route.getRouteType() != null) {
            return Optional.of(route.getRouteType());
        }
        
        return Optional.empty();
    }

    /**
     * Get route information for a route.
     * 
     * @param routeName The route name
     * @return Optional containing Route entity if found, empty otherwise
     */
    public Optional<Route> getRoute(String routeName) {
        ensureInitialized();
        
        if (routeName == null || routeName.isBlank()) {
            return Optional.empty();
        }
        
        Route route = routeCache.get(routeName.toUpperCase());
        return Optional.ofNullable(route);
    }

    /**
     * Get an available locate route for a symbol.
     * Uses hash-based selection from available locate routes.
     * 
     * @param symbol The stock symbol
     * @return A locate route name, or null if none available
     */
    @Transactional(readOnly = true)
    public String getAvailableLocateRoute(String symbol) {
        ensureInitialized();
        
        List<Route> locateRoutes = routeRepository.findByActiveTrue().stream()
                .filter(r -> r.getRouteType() != null)
                .sorted((r1, r2) -> {
                    int priorityCompare = Integer.compare(
                            r1.getPriority() != null ? r1.getPriority() : 0,
                            r2.getPriority() != null ? r2.getPriority() : 0);
                    if (priorityCompare != 0) {
                        return priorityCompare;
                    }
                    return r1.getName().compareTo(r2.getName());
                })
                .toList();
        
        if (locateRoutes.isEmpty()) {
            return null;
        }
        
        // Use hash-based selection for load balancing
        int index = Math.abs(symbol.hashCode()) % locateRoutes.size();
        return locateRoutes.get(index).getName();
    }

    /**
     * Refresh the cache (call after route changes).
     */
    public void refreshCache() {
        loadRoutes();
    }

    /**
     * Ensure cache is initialized.
     */
    private void ensureInitialized() {
        if (!initialized) {
            log.warn("Route cache not initialized, loading routes...");
            loadRoutes();
        }
    }
}

