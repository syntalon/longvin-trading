# Testing the Acceptor Session

The acceptor session is configured to listen on **port 9877** at **IP 173.69.33.84** for FIX.4.2 connections.

## Current Configuration

- **Port**: 9877
- **IP**: 173.69.33.84 (listening on all interfaces: `*.9877`)
- **FIX Version**: FIX.4.2
- **SenderCompID**: PRIMARY
- **TargetCompID**: DAS_DROP_COPY

## Testing Methods

### 1. Basic Connectivity Test (✓ Already Verified)

The test `AcceptorSessionTest` confirms the acceptor is listening:

```bash
./mvnw test -Dtest=AcceptorSessionTest -pl backend
```

### 2. Monitor Logs in Real-Time

Watch for incoming connections and logon events:

```bash
# Application logs
tail -f backend/target/quickfix/log/*.log

# Or if using Spring Boot logging
tail -f logs/application.log
```

### 3. Check QuickFIX/J Event Logs

The event log shows session lifecycle events:

```bash
# Find the acceptor session event log
ls -la backend/target/quickfix/log/

# Monitor the acceptor session log (session ID format: FIX.4.2-DAS_DROP_COPY-PRIMARY)
tail -f backend/target/quickfix/log/FIX.4.2-DAS_DROP_COPY-PRIMARY.event.log
```

### 4. Verify Port is Listening

```bash
netstat -an | grep 9877
# Should show: tcp46  0  0  *.9877  *.*  LISTEN
```

### 5. Test from DAS Trader

Since DAS Trader confirmed the configuration:
- They should initiate a connection to `173.69.33.84:9877`
- You should see log entries like:
  - `Created FIX session FIX.4.2:DAS_DROP_COPY->PRIMARY`
  - `Logged on to FIX session FIX.4.2:DAS_DROP_COPY->PRIMARY`
- Check the `onLogon` callback in `OrderReplicationCoordinator.java`

### 6. Expected Behavior When DAS Trader Connects

When DAS Trader connects, you should see:

1. **Connection established**: TCP connection accepted
2. **Session created**: `onCreate()` callback triggered
3. **Logon received**: `fromAdmin()` with Logon message (MsgType=A)
4. **Session logged on**: `onLogon()` callback triggered
5. **Heartbeats**: Regular heartbeat exchanges (every 30 seconds)
6. **Drop-copy messages**: Execution reports and other messages via `fromApp()`

### 7. Troubleshooting

If DAS Trader cannot connect:

1. **Firewall**: Ensure port 9877 is open on the server
2. **Network**: Verify IP 173.69.33.84 is accessible from DAS Trader's network
3. **Configuration mismatch**: 
   - Verify DAS Trader is using FIX.4.2
   - Verify their SenderCompID matches your TargetCompID (DAS_DROP_COPY)
   - Verify their TargetCompID matches your SenderCompID (PRIMARY)
4. **Check logs**: Look for connection errors or rejections in the event log

### 8. Manual Test with FIX Client Tool

If you have access to a FIX testing tool (like QuickFIX/J's example applications):

```bash
# You would need to configure a test initiator to connect to:
# Host: 173.69.33.84
# Port: 9877
# BeginString: FIX.4.2
# SenderCompID: DAS_DROP_COPY (or whatever matches your config)
# TargetCompID: PRIMARY
```

## Current Status

✅ Acceptor session is **listening** on port 9877
✅ Basic TCP connectivity test **passed**
⏳ Waiting for DAS Trader to connect (they confirmed configuration is ready)

