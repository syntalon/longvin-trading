# How to Monitor Messages from DAS Trader Drop Copy Session

This guide shows you how to verify that you're receiving messages from DAS Trader through the drop copy session.

## Method 1: REST API Endpoint (Recommended)

A REST endpoint shows all recent messages received from the drop copy session:

```bash
# Get recent messages from drop copy session
curl http://localhost:8080/api/fix/sessions/drop-copy/messages

# Or with pretty JSON (if you have jq)
curl http://localhost:8080/api/fix/sessions/drop-copy/messages | jq
```

### Example Response:

```json
{
  "totalMessages": 5,
  "messages": [
    {
      "sessionId": "FIX.4.2:PRIMARY->DAS_DROP_COPY",
      "msgType": "8",
      "msgTypeName": "ExecutionReport",
      "msgSeqNum": 123,
      "timestamp": "2025-11-20T14:30:15.123Z"
    },
    {
      "sessionId": "FIX.4.2:PRIMARY->DAS_DROP_COPY",
      "msgType": "8",
      "msgTypeName": "ExecutionReport",
      "msgSeqNum": 124,
      "timestamp": "2025-11-20T14:30:16.456Z"
    }
  ],
  "lastMessage": {
    "msgTypeName": "ExecutionReport",
    "msgSeqNum": 124,
    "timestamp": "2025-11-20T14:30:16.456Z"
  }
}
```

**If no messages received yet:**
```json
{
  "totalMessages": 0,
  "messages": [],
  "message": "No messages received from drop copy session yet. Waiting for DAS Trader to send messages."
}
```

## Method 2: Application Logs

All messages from the drop copy session are logged with a special prefix `📥`:

```bash
# Watch for incoming messages
tail -f logs/application.log | grep "📥"

# Or watch all drop copy activity
tail -f logs/application.log | grep -i "drop copy\|DAS_DROP_COPY"
```

### What You'll See:

**For any message:**
```
2025-11-20 14:30:15.123 INFO  [OrderReplicationCoordinator] 📥 Received ExecutionReport from DAS Trader drop copy session FIX.4.2:PRIMARY->DAS_DROP_COPY - MsgSeqNum: 123
```

**For ExecutionReport messages (with details):**
```
2025-11-20 14:30:15.123 INFO  [OrderReplicationCoordinator] 📥 Received ExecutionReport from DAS Trader drop copy session FIX.4.2:PRIMARY->DAS_DROP_COPY - MsgSeqNum: 123
2025-11-20 14:30:15.124 INFO  [OrderReplicationCoordinator]   └─ ExecutionReport details: ExecType=F, ExecID=EXEC123, OrderID=ORD456, Symbol=AAPL
```

### Message Types You Might See:

- **ExecutionReport (8)**: Trade executions, fills, order status updates
- **NewOrderSingle (D)**: New orders (if DAS Trader sends them)
- **Heartbeat (0)**: Keep-alive messages (every 30 seconds)
- **Logon (A)**: Initial connection
- **Logout (5)**: Disconnection

## Method 3: QuickFIX/J Message Logs

QuickFIX/J logs all incoming and outgoing messages:

```bash
# Find the drop copy session message log
ls -la backend/target/quickfix/log/

# Watch incoming messages (look for messages with TargetCompID=PRIMARY)
tail -f backend/target/quickfix/log/FIX.4.2-PRIMARY-DAS_DROP_COPY.messages.log
```

The message log shows the raw FIX messages in a readable format.

## Method 4: Real-Time Monitoring Script

Create a simple monitoring script:

```bash
#!/bin/bash
# monitor-drop-copy.sh

echo "Monitoring drop copy messages..."
echo "Press Ctrl+C to stop"
echo ""

while true; do
    # Check REST endpoint
    response=$(curl -s http://localhost:8080/api/fix/sessions/drop-copy/messages)
    total=$(echo $response | jq -r '.totalMessages')
    lastMsg=$(echo $response | jq -r '.lastMessage.msgTypeName // "none"')
    lastTime=$(echo $response | jq -r '.lastMessage.timestamp // "N/A"')
    
    echo "[$(date +%H:%M:%S)] Total messages: $total | Last: $lastMsg at $lastTime"
    sleep 5
done
```

## Method 5: Check Message Processing

The application processes ExecutionReport messages and logs additional details:

```bash
# Watch for execution report processing
tail -f logs/application.log | grep -i "execution\|execid\|orderid"
```

You should see logs showing:
- Execution reports being processed
- Order IDs and execution IDs
- Account information
- Symbol and quantity details

## What to Expect

### When DAS Trader is Connected but Not Sending Messages:

- Session status: `"isLoggedOn": true`
- Messages endpoint: `"totalMessages": 0`
- Logs: Only heartbeats, no application messages

### When DAS Trader is Sending Messages:

- Session status: `"isLoggedOn": true`
- Messages endpoint: `"totalMessages" > 0` and increasing
- Logs: `📥 Received ...` messages appearing regularly
- ExecutionReport details logged for each execution

### Typical Message Flow:

1. **Connection**: Logon message
2. **Heartbeats**: Every 30 seconds (keep-alive)
3. **ExecutionReports**: When trades occur:
   - New order fills (ExecType=F)
   - Partial fills
   - Order cancellations (ExecType=4)
   - Order rejections (ExecType=8)

## Troubleshooting

### No Messages Received

If `totalMessages` stays at 0:

1. **Verify session is logged on:**
   ```bash
   curl http://localhost:8080/api/fix/sessions/drop-copy
   ```
   Should show `"isLoggedOn": true`

2. **Check if DAS Trader is configured to send:**
   - Verify DAS Trader has drop copy enabled
   - Check their configuration matches your session IDs

3. **Check logs for errors:**
   ```bash
   tail -f logs/application.log | grep -i "error\|exception\|reject"
   ```

4. **Verify message routing:**
   - Check that DAS Trader's TargetCompID is `PRIMARY`
   - Check that DAS Trader's SenderCompID is `DAS_DROP_COPY`

### Messages Received But Not Processed

If messages appear in logs but aren't being processed:

1. Check for filtering logic in `handleExecutionReport()`
2. Verify account matching (if configured)
3. Check for duplicate execution ID filtering
4. Review trace-level logs for "Ignoring execution report" messages

## Summary

**Quick Check:**
```bash
# 1. Check session status
curl http://localhost:8080/api/fix/sessions/drop-copy | jq '.isLoggedOn'

# 2. Check recent messages
curl http://localhost:8080/api/fix/sessions/drop-copy/messages | jq '.totalMessages'

# 3. Watch logs in real-time
tail -f logs/application.log | grep "📥"
```

If you see:
- ✅ `"isLoggedOn": true`
- ✅ `"totalMessages" > 0` and increasing
- ✅ `📥 Received ...` messages in logs

Then you're **successfully receiving messages from DAS Trader**! 🎉

