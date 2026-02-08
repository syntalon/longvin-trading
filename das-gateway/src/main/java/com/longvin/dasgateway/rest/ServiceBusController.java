package com.longvin.dasgateway.rest;

import com.longvin.dasgateway.servicebus.ServiceBusSenderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/bus")
public class ServiceBusController {

    private final ServiceBusSenderService sender;

    public ServiceBusController(ServiceBusSenderService sender) {
        this.sender = sender;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'message'"));
        }
        sender.send(message);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }
}
