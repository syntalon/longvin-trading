# How to Confirm Drop Copy Session is Created

This guide shows multiple ways to verify that the drop copy acceptor session is created and active.

## Method 1: REST API Endpoint (Recommended)

A REST endpoint is available to check the drop copy session status:

```bash
# Check drop copy session status
curl http://localhost:8080/api/fix/sessions/drop-copy

# Or in a browser:
# http://localhost:8080/api/fix/sessions/drop-copy
```

### Expected Responses:

**Before DAS Trader connects:**
```json
{
  "exists": false,
  "status": "NOT_CONNECTED",
  "message": "Drop copy session not found. The acceptor is listening on port 9877, waiting for DAS Trader to connect.",
  "expectedConfig": {
    "port": 9877,
    "ip": "173.69.33.84",
    "fixVersion": "FIX.4.2",
    "senderCompID": "PRIMARY",
    "targetCompID": "DAS_DROP_COPY"
  }
}
```

**After DAS Trader connects and logs on:**
```json
{
  "exists": true,
  "sessionId": "FIX.4.2:PRIMARY->DAS_DROP_COPY",
  "isLoggedOn": true,
  "isEnabled": true,
  "connectionType": "acceptor",
  "status": "ACTIVE",
  "message": "Drop copy session is active and logged on"
}
```

**If connected but not logged on:**
```json
{
  "exists": true,
  "sessionId": "FIX.4.2:PRIMARY->DAS_DROP_COPY",
  "isLoggedOn": false,
  "isEnabled": true,
  "connectionType": "acceptor",
  "status": "CONNECTED_BUT_NOT_LOGGED_ON",
  "message": "Session exists but not logged on yet"
}
```

## Method 2: Check Application Logs

Monitor the application logs for session lifecycle events:

```bash
# Watch application logs
tail -f logs/application.log | grep -i "FIX\|session\|logon\|drop"

# Or watch all logs
tail -f logs/application.log
```

### What to Look For:

1. **Session Created:**
   ```
   Created FIX session FIX.4.2:PRIMARY->DAS_DROP_COPY
   ```

2. **Session Logged On:**
   ```
   Logged on to FIX session FIX.4.2:PRIMARY->DAS_DROP_COPY
   ```

3. **Incoming Logon Message:**
   ```
   Received Logon message from FIX.4.2:PRIMARY->DAS_DROP_COPY
   ```

4. **Heartbeats (confirms active connection):**
   ```
   Received Heartbeat from FIX.4.2:PRIMARY->DAS_DROP_COPY
   Sent Heartbeat to FIX.4.2:PRIMARY->DAS_DROP_COPY
   ```

## Method 3: Check QuickFIX/J Event Logs

QuickFIX/J creates event logs for each session:

```bash
# List all session logs
ls -la backend/target/quickfix/log/

# Watch the drop copy session event log
# Format: FIX.4.2-PRIMARY-DAS_DROP_COPY.event.log
tail -f backend/target/quickfix/log/FIX.4.2-PRIMARY-DAS_DROP_COPY.event.log
```

The event log will show:
- Session creation
- Logon events
- Heartbeat exchanges
- Any errors or disconnections

## Method 4: Verify Port is Listening

Confirm the acceptor is listening on the correct port:

```bash
# Check if port 9877 is listening
netstat -an | grep 9877

# Should show:
# tcp46  0  0  *.9877  *.*  LISTEN
```

## Method 5: Programmatic Check (Java)

If you need to check programmatically in your code:

```java
import quickfix.Session;
import quickfix.SessionID;

SessionID dropCopySessionId = new SessionID("FIX.4.2", "PRIMARY", "DAS_DROP_COPY");
Session session = Session.lookupSession(dropCopySessionId);

if (session != null && session.isLoggedOn()) {
    // Drop copy session is active
    System.out.println("Drop copy session is ACTIVE");
} else if (session != null) {
    // Session exists but not logged on
    System.out.println("Drop copy session exists but not logged on");
} else {
    // Session not found - waiting for connection
    System.out.println("Drop copy session not found");
}
```

## Method 6: Check Session in OrderReplicationCoordinator

The `OrderReplicationCoordinator` tracks sessions in `sessionsBySenderCompId`. When a session is created and logged on, it's added to this map.

Look for log entries:
- `Created FIX session FIX.4.2:PRIMARY->DAS_DROP_COPY`
- `Logged on to FIX session FIX.4.2:PRIMARY->DAS_DROP_COPY`

## Quick Verification Checklist

- [ ] Port 9877 is listening (`netstat -an | grep 9877`)
- [ ] REST endpoint shows session status (`curl http://localhost:8080/api/fix/sessions/drop-copy`)
- [ ] Application logs show "Created FIX session" message
- [ ] Application logs show "Logged on to FIX session" message
- [ ] Heartbeats are being exchanged (check logs)
- [ ] QuickFIX/J event log exists and shows activity

## Troubleshooting

### Session Not Found

If the REST endpoint shows `"exists": false`:

1. **Check if acceptor is running:**
   ```bash
   # Check application logs for startup messages
   grep "FIX acceptor started" logs/application.log
   ```

2. **Verify configuration:**
   - Check `das-mirror-trading.cfg` has the acceptor session configured
   - Verify port 9877 is not blocked by firewall
   - Confirm IP 173.69.33.84 is correct

3. **Wait for DAS Trader:**
   - The session won't exist until DAS Trader connects
   - The acceptor is listening and waiting for the connection

### Session Exists But Not Logged On

If `"isLoggedOn": false`:

1. Check for logon rejections in logs
2. Verify FIX version matches (should be FIX.4.2)
3. Verify CompIDs match:
   - DAS Trader's SenderCompID should be `DAS_DROP_COPY`
   - DAS Trader's TargetCompID should be `PRIMARY`
4. Check for sequence number issues
5. Look for error messages in the event log

### Session Logs Out

If the session logs out after connecting:

1. Check heartbeat intervals match
2. Verify network connectivity
3. Check for sequence number mismatches
4. Review the event log for the logout reason

## Summary

The **easiest way** to confirm drop copy session is created:

```bash
curl http://localhost:8080/api/fix/sessions/drop-copy | jq
```

If `"exists": true` and `"isLoggedOn": true`, the drop copy session is **active and ready** to receive messages!

