package com.longvin.trading.rest;

import com.longvin.trading.fix.DropCopyApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import quickfix.Session;
import quickfix.SessionID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/fix/sessions")
public class FixSessionStatusController {

    private final DropCopyApplication dropCopyApplication;

    public FixSessionStatusController(DropCopyApplication dropCopyApplication) {
        this.dropCopyApplication = dropCopyApplication;
    }

    @GetMapping("/drop-copy")
    public Map<String, Object> getDropCopyStatus() {
        Map<String, Object> result = new HashMap<>();
        
        // Create the expected drop copy session ID
        // Format: BeginString-SenderCompID-TargetCompID
        SessionID dropCopySessionId = new SessionID("FIX.4.2", "OS111", "DAST");
        
        try {
            Session session = Session.lookupSession(dropCopySessionId);
            
            if (session != null) {
                result.put("exists", true);
                result.put("sessionId", dropCopySessionId.toString());
                result.put("isLoggedOn", session.isLoggedOn());
                result.put("isEnabled", session.isEnabled());
                result.put("connectionType", "acceptor");
                
                if (session.isLoggedOn()) {
                    result.put("status", "ACTIVE");
                    result.put("message", "Drop copy session is active and logged on");
                } else if (session.isEnabled()) {
                    result.put("status", "CONNECTED_BUT_NOT_LOGGED_ON");
                    result.put("message", "Session exists but not logged on yet");
                } else {
                    result.put("status", "DISABLED");
                    result.put("message", "Session exists but is disabled");
                }
            } else {
                result.put("exists", false);
                result.put("status", "NOT_CONNECTED");
                result.put("message", "Drop copy session not found. The acceptor is listening on port 9877, waiting for DAS Trader to connect.");
                result.put("expectedConfig", Map.of(
                    "port", 9877,
                    "ip", "173.69.33.84",
                    "fixVersion", "FIX.4.2",
                    "senderCompID", "OS111",
                    "targetCompID", "DAST"
                ));
            }
        } catch (Exception e) {
            result.put("exists", false);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    @GetMapping("/drop-copy/messages")
    public Map<String, Object> getDropCopyMessages() {
        Map<String, Object> result = new HashMap<>();
        
        List<DropCopyApplication.ReceivedMessage> messages = dropCopyApplication.getRecentDropCopyMessages();
        
        result.put("totalMessages", messages.size());
        result.put("messages", messages.stream().map(msg -> Map.of(
            "sessionId", msg.getSessionId(),
            "msgType", msg.getMsgType(),
            "msgTypeName", msg.getMsgTypeName(),
            "msgSeqNum", msg.getMsgSeqNum(),
            "timestamp", msg.getTimestamp().toString()
        )).collect(Collectors.toList()));
        
        if (messages.isEmpty()) {
            result.put("message", "No messages received from drop copy session yet. Waiting for DAS Trader to send messages.");
        } else {
            DropCopyApplication.ReceivedMessage lastMessage = messages.get(messages.size() - 1);
            result.put("lastMessage", Map.of(
                "msgTypeName", lastMessage.getMsgTypeName(),
                "msgSeqNum", lastMessage.getMsgSeqNum(),
                "timestamp", lastMessage.getTimestamp().toString()
            ));
        }
        
        return result;
    }
}

