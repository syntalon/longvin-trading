# FIX Message Types Reference

## Common FIX Message Types

| Type | Name | Description |
|------|------|-------------|
| **0** | Heartbeat | Keep-alive message sent periodically |
| **1** | TestRequest | Request for a heartbeat response |
| **2** | ResendRequest | Request to resend missing messages |
| **3** | Reject | Message rejection |
| **4** | SequenceReset | Reset sequence numbers |
| **5** | Logout | Disconnect from session |
| **A** | Logon | Connect to session |
| **D** | NewOrderSingle | New order submission |
| **8** | ExecutionReport | Order execution/fill report |

## Direction vs Message Type

**Important:** Message type does NOT indicate direction!

- **Message Type** = What kind of message (Heartbeat, Logon, ExecutionReport, etc.)
- **Direction** = Determined by the callback method:
  - `toAdmin()` / `toApp()` = **OUTGOING** (we're sending)
  - `fromAdmin()` / `fromApp()` = **INCOMING** (we're receiving)

### Example: Heartbeat (Type 0)

```java
// OUTGOING heartbeat (we send it)
@Override
public void toAdmin(Message message, SessionID sessionID) {
    if ("0".equals(msgType)) {
        log.info("Sending Heartbeat...");  // We're sending
    }
}

// INCOMING heartbeat (we receive it)
@Override
public void fromAdmin(Message message, SessionID sessionID) {
    if ("0".equals(msgType)) {
        log.info("Received Heartbeat...");  // We're receiving
    }
}
```

Both use message type "0", but the method name shows direction!

