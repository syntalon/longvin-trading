package com.longvin.trading.rest;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class UiForwardController {

    /**
     * Forward root path to index.html
     */
    @GetMapping("/")
    public String forwardIndex() {
        return "forward:/index.html";
    }

    /**
     * Catch-all handler for Angular routes.
     * Forwards all non-API routes to index.html so Angular can handle client-side routing.
     * This allows refreshing the page on any Angular route without getting 404 errors.
     * 
     * Note: This will match any path that doesn't start with /api.
     * API routes are handled by @RestController classes with @RequestMapping("/api/**").
     */
    @RequestMapping(value = {
        "/copy-rules",
        "/copy-rules/**",
        "/logs",
        "/logs/**"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
