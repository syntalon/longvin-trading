package com.longvin.dasgateway.rest;

import com.longvin.dasgateway.cmd.CmdClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/cmd")
public class CmdController {

    private final CmdClient client;

    public CmdController(CmdClient client) {
        this.client = client;
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody Map<String, String> body) throws Exception {
        String command = body.get("command");
        if (command == null || command.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'command'"));
        }
        client.sendRaw(command);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }
}
