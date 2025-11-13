package com.longvin.trading.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiForwardController {

    @GetMapping("/")
    public String forwardIndex() {
        return "forward:/index.html";
    }
}
