# Sell Short Order Flow

This document describes the complete flow for processing sell short orders from DAS Trader Pro.

## Overview

When a user enters a sell short order in DAS Trader Pro for the parent/primary account, the following flow occurs:

1. **Receive ExecutionReport (NEW)** via acceptor session
2. **Create draft orders** for all shadow accounts (persist but don't send yet)
3. **Send Locate Request** via initiator session to check stock availability
4. **Receive Locate Response** via acceptor session
5. **Borrow Stock** and update database
6. **Send Sell Short Orders** for shadow accounts via initiator session
7. **Persist** shadow order ExecutionReports

## Detailed Flow

### Step 1: User Enters Sell Short Order in DAS Trader Pro

- User enters: Symbol=AAPL, Side=SELL_SHORT (5), Quantity=100, Account=PRIMARY_ACCOUNT
- DAS Trader sends ExecutionReport with ExecType=NEW (0) via drop copy session

### Step 2: Acceptor Receives ExecutionReport (NEW)

**Location**: `AcceptorMessageProcessor.processIncomingApp()`

- ExecutionReport arrives with:
  - ExecType = NEW (0)
  - OrdStatus = NEW (0)
  - Side = SELL_SHORT (5)
  - Symbol = AAPL
  - OrderQty = 100
  - Account = PRIMARY_ACCOUNT

**Actions**:
1. Log the incoming ExecutionReport
2. Delegate to `DropCopyReplicationService.processExecutionReport()`

### Step 3: Persist Primary Order

**Location**: `DropCopyReplicationService.processExecutionReport()`

**Actions**:
1. Call `OrderPersistenceService.createOrderEvent()` to persist:
   - OrderEvent (immutable event)
   - Order entity for primary account (status = NEW)

### Step 4: Create Draft Orders for Shadow Accounts

**Location**: `DropCopyReplicationService.handleNewExecution()`

**Actions**:
1. Detect it's a short order (Side = SELL_SHORT or SELL_SHORT_EXEMPT)
2. **NEW**: Create draft Order entities for all shadow accounts:
   - For each shadow account:
     - Create Order entity with:
       - Account = shadow account
       - Symbol = AAPL
       - Side = SELL_SHORT (5)
       - OrderQty = 100
       - OrdStatus = DRAFT (or PENDING_LOCATE)
       - OrderGroup = same group as primary order
     - Persist draft order
   - Link all orders in same OrderGroup

### Step 5: Send Locate Request

**Location**: `DropCopyReplicationService.processShortOrderWithLocate()`

**Actions**:
1. Get Order entity from database
2. Call `ShortOrderProcessingService.processShortOrder()`:
   - Create LocateRequest entity:
     - Order = primary order
     - Symbol = AAPL
     - Quantity = 100
     - Status = PENDING
   - Persist LocateRequest
   - Send FIX LocateRequest (MsgType=L) via initiator session:
     - ClOrdID = LocateReqID
     - Symbol = AAPL
     - OrderQty = 100
     - Account = PRIMARY_ACCOUNT

### Step 6: Receive Locate Response

**Location**: `LocateResponseHandler.processLocateResponse()`

- ExecutionReport arrives with:
  - MsgType = M (LocateResponse)
  - ClOrdID = LocateReqID (matches request)
  - OrdStatus = 0 (Approved) or 8 (Rejected)
  - OrderQty = available quantity
  - OrderID = LocateID

**Actions**:
1. Extract LocateReqID from ClOrdID
2. Find LocateRequest by FixLocateReqId
3. Call `ShortOrderProcessingService.processLocateResponseByLocateReqId()`

### Step 7: Process Locate Response - Borrow Stock & Update Database

**Location**: `ShortOrderProcessingService.processLocateResponse()`

**Actions**:
1. **Validate Response**:
   - Check if approved
   - Check if available quantity >= requested quantity
   
2. **Update LocateRequest**:
   - Status = APPROVED
   - AvailableQty = available quantity from response
   - LocateID = OrderID from response
   - ResponseMessage = text from response
   - Persist LocateRequest

3. **Borrow Stock**:
   - Call `borrowStock()`:
     - Status = BORROWED
     - BorrowedQty = requested quantity
     - Persist LocateRequest

4. **Notify Success**:
   - Call `ShortLocateCoordinator.completeSuccess()`
   - This triggers shadow order placement

### Step 8: Send Sell Short Orders for Shadow Accounts

**Location**: `DropCopyReplicationService` (after locate success)

**Actions**:
1. **Wait for Locate Approval** (if using async coordinator):
   - Await `ShortLocateCoordinator` completion
   - Or proceed immediately if synchronous

2. **For Each Shadow Account**:
   - Get draft Order entity for shadow account
   - Build NewOrderSingle:
     - ClOrdID = generated mirror ClOrdID
     - Symbol = AAPL
     - Side = SELL_SHORT (5)
     - OrderQty = 100
     - Account = shadow account number
     - Price, TimeInForce, etc. from primary order
   - Send via initiator session
   - Update draft Order:
     - OrdStatus = NEW (0)
     - FixClOrdID = generated ClOrdID
     - Persist Order

### Step 9: Receive ExecutionReports for Shadow Orders

**Location**: `InitiatorMessageProcessor.processIncomingApp()`

- ExecutionReport arrives for each shadow order:
  - ExecType = NEW (0) or FILL (2)
  - OrdStatus = NEW (0) or FILLED (2)
  - ClOrdID = shadow order ClOrdID
  - Account = shadow account number

**Actions**:
1. Persist ExecutionReport as OrderEvent
2. Update Order entity status

## Database State Throughout Flow

### After Step 3 (Primary Order Persisted):
```
Order (Primary):
  - id: UUID
  - account: PRIMARY_ACCOUNT
  - symbol: AAPL
  - side: SELL_SHORT (5)
  - ordStatus: NEW (0)
  - orderQty: 100
  - orderGroup: OrderGroup

OrderGroup:
  - id: UUID
  - primaryOrder: Order (Primary)
  - orders: [Order (Primary)]
```

### After Step 4 (Draft Orders Created):
```
Order (Primary):
  - ordStatus: NEW (0)

Order (Shadow 1) - DRAFT:
  - account: SHADOW_ACCOUNT_1
  - symbol: AAPL
  - side: SELL_SHORT (5)
  - ordStatus: DRAFT (or PENDING_LOCATE)
  - orderQty: 100
  - orderGroup: same OrderGroup

Order (Shadow 2) - DRAFT:
  - account: SHADOW_ACCOUNT_2
  - symbol: AAPL
  - side: SELL_SHORT (5)
  - ordStatus: DRAFT (or PENDING_LOCATE)
  - orderQty: 100
  - orderGroup: same OrderGroup

OrderGroup:
  - orders: [Order (Primary), Order (Shadow 1), Order (Shadow 2)]

LocateRequest:
  - order: Order (Primary)
  - symbol: AAPL
  - quantity: 100
  - status: PENDING
```

### After Step 7 (Stock Borrowed):
```
LocateRequest:
  - status: BORROWED
  - availableQty: 100
  - borrowedQty: 100
  - locateId: LOCATE-12345
```

### After Step 8 (Shadow Orders Sent):
```
Order (Shadow 1):
  - ordStatus: NEW (0)
  - fixClOrdId: MIRROR-SHADOW1-ORDER123-N

Order (Shadow 2):
  - ordStatus: NEW (0)
  - fixClOrdId: MIRROR-SHADOW2-ORDER123-N
```

## Key Components

1. **AcceptorMessageProcessor**: Receives ExecutionReports from DAS Trader
2. **DropCopyReplicationService**: Orchestrates replication flow
3. **OrderPersistenceService**: Persists OrderEvent and Order entities
4. **ShortOrderProcessingService**: Handles locate requests and stock borrowing
5. **LocateResponseHandler**: Processes locate responses
6. **ShortLocateCoordinator**: Coordinates async locate request completion

## Status Values

- **Order.ordStatus**:
  - '0' = NEW
  - '1' = PARTIALLY_FILLED
  - '2' = FILLED
  - 'D' = DRAFT (custom, if needed)
  
- **LocateRequest.status**:
  - PENDING = Waiting for locate response
  - APPROVED = Locate approved
  - BORROWED = Stock borrowed
  - REJECTED = Locate rejected
  - EXPIRED = Locate timed out

## Error Handling

- **Locate Rejected**: Mark LocateRequest as REJECTED, do not send shadow orders
- **Locate Timeout**: Mark LocateRequest as EXPIRED, do not send shadow orders
- **Insufficient Quantity**: Mark LocateRequest as REJECTED, do not send shadow orders
- **Shadow Order Send Failure**: Log error, retry if needed

