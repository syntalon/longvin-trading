# Short Order Processing Guide

## Overview

This guide explains how the short order processing system works, including locate requests, stock borrowing, and shadow order placement.

## Architecture

### Components

1. **OrderPersistenceService** - Persists ExecutionReports as OrderEvent and Order entities
2. **ShortOrderProcessingService** - Handles short order workflow (locate → borrow → place orders)
3. **LocateRequestMonitoringService** - Monitors pending locate requests and handles timeouts
4. **LocateResponseHandler** - Processes locate response messages from broker
5. **DropCopyReplicationService** - Detects short orders and triggers locate workflow

### Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. ExecutionReport (short order) received from DAS Trader     │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. OrderPersistenceService.createOrderEvent()                  │
│    - Creates OrderEvent (immutable event)                      │
│    - Creates/updates Order (current state)                      │
│    - Links to Account entity                                   │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. DropCopyReplicationService.handleNewExecution()              │
│    - Detects short order (Side='5' or '6')                     │
│    - Retrieves Order from database                             │
│    - Calls ShortOrderProcessingService.processShortOrder()      │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. ShortOrderProcessingService.processShortOrder()              │
│    - Creates LocateRequest entity (status: PENDING)             │
│    - Persists LocateRequest to database                         │
│    - Sends FIX locate request (MsgType="L")                    │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. [Wait for broker response]                                   │
│    - LocateRequestMonitoringService checks for timeouts         │
│    - Pending requests > 30 seconds → EXPIRED                    │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. LocateResponseHandler.processLocateResponse()                │
│    - Receives locate response (MsgType="M")                     │
│    - Extracts: approved, availableQty, locateId                 │
│    - Calls ShortOrderProcessingService.processLocateResponse() │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 7. ShortOrderProcessingService.processLocateResponse()          │
│    - Updates LocateRequest (status: APPROVED)                   │
│    - Borrows stock (status: BORROWED)                          │
│    - Retrieves shadow accounts from database                    │
│    - Places short orders for shadow accounts                    │
└─────────────────────────────────────────────────────────────────┘
```

## Locate Request Statuses

- **PENDING** - Locate request sent, waiting for broker response
- **APPROVED** - Locate approved, stock available
- **REJECTED** - Locate rejected (insufficient quantity, error, etc.)
- **BORROWED** - Stock successfully borrowed
- **EXPIRED** - Locate request timed out (>30 seconds)
- **CANCELLED** - Locate request cancelled

## Error Handling

### Retry Logic

- Locate request sending failures are retried up to 3 times
- Retries occur if session is not logged on or network errors occur
- After 3 failed attempts, request is marked as REJECTED

### Timeout Handling

- `LocateRequestMonitoringService` runs every 10 seconds
- Checks for pending locate requests older than 30 seconds
- Automatically marks expired requests as EXPIRED

### Error Recovery

- All errors are logged with full context
- Failed shadow order placements are logged but don't stop other placements
- Locate request status is always updated to reflect errors

## Monitoring

### REST Endpoints

- `GET /api/locate-requests` - Get all locate requests
- `GET /api/locate-requests/{id}` - Get specific locate request
- `GET /api/locate-requests/status/{status}` - Get requests by status
- `GET /api/locate-requests/pending` - Get pending requests (for monitoring)
- `GET /api/locate-requests/order/{orderId}` - Get requests for an order

### Logging

Key log messages to monitor:

- `"Processing short order"` - Short order detected
- `"Sent locate request"` - Locate request sent successfully
- `"Processing locate response"` - Locate response received
- `"Stock borrowed successfully"` - Stock borrowing completed
- `"Placed shadow order"` - Shadow order placed successfully
- `"Marking locate request as EXPIRED"` - Timeout detected

## Testing

### With Simulator

1. Start the simulator: `cd simulator && mvn spring-boot:run`
2. Start the trading app: `cd backend && mvn spring-boot:run -Dspring.profiles.active=simulator`
3. Send a short order ExecutionReport from simulator GUI
4. Monitor logs for locate request workflow
5. Send locate response from simulator (if supported)

### With Production Broker

1. Ensure primary account is configured in database
2. Ensure shadow accounts are configured (AccountType=SHADOW)
3. Monitor `/api/locate-requests/pending` endpoint
4. Check logs for locate request/response messages
5. Verify shadow orders are placed after borrowing

## Configuration

### Locate Request Timeout

Default: 30 seconds

To change, modify `LOCATE_REQUEST_TIMEOUT` in `LocateRequestMonitoringService`:

```java
private static final Duration LOCATE_REQUEST_TIMEOUT = Duration.ofSeconds(60); // 60 seconds
```

### Retry Attempts

Default: 3 retries

To change, modify retry count in `ShortOrderProcessingService.sendLocateRequest()`:

```java
if (retryCount < 5) { // 5 retries instead of 3
```

## Broker-Specific Adaptations

### Locate Request Message Format

The current implementation uses:
- MsgType="L" (LocateRequest)
- ClOrdID field for LocateReqID
- Symbol, OrderQty, Account fields

**Adaptation needed**: Adjust `ShortOrderProcessingService.sendLocateRequest()` based on your broker's FIX specification.

### Locate Response Message Format

The current implementation expects:
- MsgType="M" (LocateResponse)
- ClOrdID field for LocateReqID matching
- OrderQty or LeavesQty for available quantity
- OrderID for locate ID
- Text field for response message

**Adaptation needed**: Adjust `LocateResponseHandler.processLocateResponse()` based on your broker's response format.

## Troubleshooting

### Locate Request Not Sent

- Check session is logged on: `GET /api/fix/sessions`
- Check logs for "Cannot send locate request"
- Verify Order entity exists in database

### Locate Response Not Received

- Check broker is sending MsgType="M"
- Verify LocateReqID matches (check logs)
- Check `LocateResponseHandler` is registered in `InitiatorMessageProcessor`

### Shadow Orders Not Placed

- Check locate request status: `GET /api/locate-requests/pending`
- Verify shadow accounts exist: Check database for AccountType=SHADOW
- Check logs for "Placed shadow order" messages
- Verify initiator session is logged on

### Timeout Issues

- Check `LocateRequestMonitoringService` is running (enabled by `@EnableScheduling`)
- Verify timeout duration matches broker's response time
- Check for network issues or broker delays

## Database Schema

### LocateRequest Table

- `id` (UUID) - Primary key
- `order_id` (UUID) - Foreign key to Order
- `account_id` (UUID) - Foreign key to Account
- `symbol` (String) - Symbol to locate
- `quantity` (BigDecimal) - Quantity requested
- `status` (Enum) - Current status
- `available_qty` (BigDecimal) - Quantity available (from response)
- `borrowed_qty` (BigDecimal) - Quantity borrowed
- `locate_id` (String) - Locate ID from broker
- `fix_locate_req_id` (String) - FIX LocateReqID
- `response_message` (String) - Response message
- `created_at` (Timestamp) - Creation time
- `updated_at` (Timestamp) - Last update time

## Next Steps

1. **Test with broker** - Verify locate request/response format matches broker's specification
2. **Adjust message parsing** - Update `LocateResponseHandler` based on actual broker responses
3. **Add metrics** - Track locate request success rates, average response times
4. **Add alerts** - Alert on high timeout rates or failed locate requests
5. **Optimize retries** - Implement exponential backoff for retries

