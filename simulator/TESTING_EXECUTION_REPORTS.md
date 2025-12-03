# Testing ExecutionReports with the Simulator

This guide explains how to use the simulator to test the trading app by sending ExecutionReport messages.

## Overview

The simulator can send ExecutionReport messages to the trading app's acceptor session, simulating DAS Trader's drop copy feed. This allows you to test:

- ExecutionReport processing
- Order persistence (OrderEvent and Order entities)
- Drop copy replication to shadow accounts
- Short order processing with locate requests

## Setup

### 1. Start the Trading App (with simulator profile)

```bash
cd backend
mvn spring-boot:run -Dspring.profiles.active=simulator
```

The trading app will:
- Start an acceptor session on port 9877 (listening for SIM-DAST -> SIM-OS111)
- Start an initiator session connecting to simulator's acceptor on port 8661 (SIM-OS111 -> OPAL)

### 2. Start the Simulator

```bash
cd simulator
mvn spring-boot:run
```

Or from project root:
```bash
mvn spring-boot:run -pl simulator
```

The simulator will:
- Start an acceptor session on port 8661 (for trading app's initiator)
- Start an initiator session connecting to trading app's acceptor on port 9877 (SIM-DAST -> SIM-OS111)
- Launch the GUI automatically (if `simulator.gui-enabled=true`)

## Testing with the GUI

### Step 1: Verify Sessions are Connected

1. Check the simulator GUI - the session status should show "Logged On" in green
2. Check the trading app logs - you should see:
   ```
   Received Logon request from DAS Trader (acceptor session: ...)
   ```

### Step 2: Send an ExecutionReport

1. In the simulator GUI, fill in the ExecutionReport fields:
   - **Symbol** (required): e.g., "AAPL"
   - **ExecType** (required): e.g., "FILL" (2)
   - **OrdStatus** (required): e.g., "FILLED" (2)
   - **Side** (required): e.g., "BUY" (1) or "SELL" (2)
   - **ClOrdID**: e.g., "CLORD-12345"
   - **OrderQty**: e.g., "100"
   - **Price**: e.g., "150.50"
   - **Account**: e.g., "PRIMARY_ACCOUNT"

2. Click "Fill Sample" to populate with sample data, or enter your own values

3. Click "Send ExecutionReport"

4. Check the status area in the GUI - it should show "ExecutionReport sent successfully!"

### Step 3: Verify Processing in Trading App

Check the trading app logs for:

1. **Message Reception:**
   ```
   📥 Received ExecutionReport from drop copy session: ...
   ```

2. **Order Persistence:**
   ```
   Created OrderEvent: execId=..., execType=...
   Created/Updated Order: fixOrderId=...
   ```

3. **Replication (if shadow accounts are configured):**
   ```
   Replicating NEW order to shadow accounts: ...
   ```

4. **Short Order Processing (if Side=SELL_SHORT):**
   ```
   Processing short order: symbol=..., qty=...
   Sending locate request: ...
   ```

## Testing Different Scenarios

### Test 1: New Order Fill

- **ExecType**: FILL (2)
- **OrdStatus**: FILLED (2)
- **Side**: BUY (1)
- **OrderQty**: 100
- **Price**: 150.50
- **Account**: PRIMARY_ACCOUNT

**Expected Result:**
- OrderEvent created with execType='2', ordStatus='2'
- Order created/updated with cumQty=100, leavesQty=0
- If shadow accounts exist, replicated orders sent

### Test 2: Partial Fill

- **ExecType**: PARTIAL_FILL (1)
- **OrdStatus**: PARTIALLY_FILLED (1)
- **CumQty**: 50
- **LeavesQty**: 50
- **LastQty**: 50

**Expected Result:**
- OrderEvent created with partial fill details
- Order updated with cumQty=50, leavesQty=50

### Test 3: Short Order (SELL_SHORT)

- **Side**: SELL_SHORT (5)
- **ExecType**: NEW (0)
- **OrdStatus**: NEW (0)

**Expected Result:**
- LocateRequest created with status=PENDING
- Locate request sent via initiator session
- Shadow orders placed after locate approval

### Test 4: Order Replace

- **ExecType**: REPLACED (5)
- **OrdStatus**: REPLACED (5)
- **OrigClOrdID**: (original ClOrdID)
- **ClOrdID**: (new ClOrdID)

**Expected Result:**
- OrderEvent created with execType='5'
- Order updated with new ClOrdID
- Replace orders sent to shadow accounts

### Test 5: Order Cancel

- **ExecType**: CANCELED (4)
- **OrdStatus**: CANCELED (4)
- **OrigClOrdID**: (original ClOrdID)

**Expected Result:**
- OrderEvent created with execType='4'
- Order updated with ordStatus='4'
- Cancel orders sent to shadow accounts

## Monitoring

### Check Database

Access H2 console at: http://localhost:8081/h2-console

- **JDBC URL**: `jdbc:h2:mem:tradingdb-test`
- **Username**: `sa`
- **Password**: (empty)

Query tables:
```sql
-- View all order events
SELECT * FROM order_events ORDER BY event_time DESC;

-- View all orders
SELECT * FROM orders ORDER BY created_at DESC;

-- View locate requests
SELECT * FROM locate_requests ORDER BY created_at DESC;
```

### Check Logs

The trading app logs all ExecutionReport processing:
- Incoming messages: `AcceptorMessageProcessor`
- Persistence: `OrderPersistenceService`
- Replication: `DropCopyReplicationService`
- Short orders: `ShortOrderProcessingService`

## Troubleshooting

### Simulator GUI shows "Session: Not Found"

- Ensure the simulator is running
- Check that the initiator session (SIM-DAST -> SIM-OS111) is logged on
- Verify simulator config matches trading app config

### "Cannot send ExecutionReport: session is not logged on"

- Wait for both sessions to log on (check logs)
- Verify trading app acceptor is listening on port 9877
- Check firewall/network connectivity

### Trading app doesn't receive messages

- Verify CompIDs match:
  - Simulator initiator: SenderCompID=SIM-DAST, TargetCompID=SIM-OS111
  - Trading app acceptor: SenderCompID=SIM-OS111, TargetCompID=SIM-DAST
- Check sequence numbers aren't mismatched (delete store files if needed)
- Verify both apps are using FIX.4.2

### Messages received but not processed

- Check logs for "No processor found" errors
- Verify the session is identified as drop copy session
- Check that `drop-copy-session-sender-comp-id` and `drop-copy-session-target-comp-id` are configured correctly

## Configuration Files

- **Trading App (simulator profile)**: `backend/src/main/resources/fix/das-mirror-trading-test.cfg`
- **Simulator**: `simulator/src/main/resources/fix/simulator.cfg`

Both should use:
- `BeginString=FIX.4.2`
- Matching CompIDs (SIM-DAST, SIM-OS111)
- Port 9877 for drop copy connection

