# ExecutionReport Processing Flow

## Overview
This document explains where and how ExecutionReport messages from DAS Trader are processed in the system.

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. DAS Trader sends ExecutionReport via FIX                     │
│    Session: FIX.4.2:DAST->OS111 (drop copy acceptor)            │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. QuickFIX/J Framework                                         │
│    - Receives FIX message                                       │
│    - Validates and parses message                               │
│    - Calls Application.fromApp()                                 │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. OrderReplicationCoordinator.fromApp()                       │
│    File: fix/OrderReplicationCoordinator.java:266                │
│    - Determines connection type (acceptor/initiator)            │
│    - Gets appropriate processor from factory                     │
│    - Delegates to processor.processIncomingApp()                 │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. AcceptorMessageProcessor.processIncomingApp()                │
│    File: processor/impl/AcceptorMessageProcessor.java:152      │
│    - Checks if message is ExecutionReport (MsgType="8")         │
│    - Logs ExecutionReport details                               │
│    - Finds initiator session for replication                    │
│    - Calls DropCopyReplicationService.processExecutionReport()  │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. DropCopyReplicationService.processExecutionReport()         │
│    File: service/DropCopyReplicationService.java:62            │
│    - Validates it's from drop copy session                      │
│    - Checks for duplicate ExecID                                │
│    - Validates account (primary account only)                    │
│    - Extracts OrderID and ExecType                              │
│    - Routes to handler based on ExecType:                       │
│      • ExecType.NEW → handleNewExecution()                      │
│      • ExecType.REPLACED → handleReplaceExecution()              │
│      • ExecType.CANCELED → handleCancelExecution()               │
└────────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. DropCopyReplicationService.handleNewExecution()              │
│    File: service/DropCopyReplicationService.java:111           │
│    - Creates/updates PrimaryOrderState                          │
│    - Checks if order is short (Side='5' or '6')                 │
│    - If SHORT:                                                   │
│      → processShortOrderWithLocate()                            │
│        → ShortOrderProcessingService.processShortOrder()         │
│        → Send locate request                                    │
│        → Wait for locate response                               │
│        → Borrow stock                                           │
│        → Place shadow orders                                    │
│    - If REGULAR:                                                 │
│      → replicateNewOrderToShadows()                             │
│        → Build NewOrderSingle for each shadow account            │
│        → Send via initiator session                              │
└─────────────────────────────────────────────────────────────────┘
```

## Key Files and Methods

### Entry Point
- **`OrderReplicationCoordinator.fromApp()`** (line 266)
  - QuickFIX/J Application interface method
  - Routes messages to appropriate processors

### Processor Layer
- **`AcceptorMessageProcessor.processIncomingApp()`** (line 152)
  - Handles messages from drop copy acceptor session
  - Detects ExecutionReport (MsgType="8")
  - Delegates to replication service

### Business Logic Layer
- **`DropCopyReplicationService.processExecutionReport()`** (line 62)
  - Main processing logic
  - Validates and routes by ExecType
  
- **`DropCopyReplicationService.handleNewExecution()`** (line 111)
  - Handles new orders (ExecType=NEW)
  - Detects short orders
  - Routes to short order processing or direct replication

### Short Order Processing
- **`ShortOrderProcessingService.processShortOrder()`**
  - Creates LocateRequest entity
  - Sends FIX locate request
  - Processes locate response
  - Borrows stock
  - Places shadow orders

## Where to Add Order Persistence

**Current Gap**: ExecutionReports are processed but not persisted to database.

**Recommended Integration Points**:

1. **After receiving ExecutionReport** (in `DropCopyReplicationService.processExecutionReport()`):
   - Create `OrderEvent` entity (immutable event)
   - Create/update `Order` entity (current state)
   - Link to `Account` entity
   - Create/update `OrderGroup` if needed

2. **Before processing short order** (in `handleNewExecution()`):
   - Ensure `Order` entity exists
   - Link `LocateRequest` to `Order`

3. **When locate response received**:
   - Update `LocateRequest` status
   - Link to `Order` entity

## Example: Where to Add Persistence

```java
// In DropCopyReplicationService.processExecutionReport()
public void processExecutionReport(ExecutionReport report, SessionID sessionID, SessionID initiatorSessionID) 
        throws FieldNotFound {
    
    // ... existing validation ...
    
    // NEW: Persist ExecutionReport as OrderEvent
    OrderEvent event = orderPersistenceService.createOrderEvent(report, sessionID);
    Order order = orderPersistenceService.createOrUpdateOrder(event);
    
    // Continue with existing logic...
    char execType = report.getExecType().getValue();
    if (execType == ExecType.NEW) {
        handleNewExecution(report, account, orderId, initiatorSessionID, order); // Pass Order entity
    }
    // ...
}
```

## Message Flow Summary

1. **DAS Trader** → Sends ExecutionReport via FIX
2. **QuickFIX/J** → Receives and parses message
3. **OrderReplicationCoordinator** → Routes to processor
4. **AcceptorMessageProcessor** → Detects ExecutionReport
5. **DropCopyReplicationService** → Processes business logic
6. **ShortOrderProcessingService** → Handles short orders (if applicable)
7. **Shadow Orders** → Replicated to shadow accounts via initiator session

## Next Steps

To complete the integration:

1. Create `OrderPersistenceService` to handle:
   - Creating `OrderEvent` from ExecutionReport
   - Creating/updating `Order` entity
   - Creating/updating `OrderGroup` for primary+shadow orders

2. Integrate persistence into `DropCopyReplicationService.processExecutionReport()`

3. Update `ShortOrderProcessingService` to:
   - Accept `Order` entity instead of just order details
   - Link `LocateRequest` to persisted `Order`

4. Handle locate responses:
   - Create message handler for locate response (MsgType="M" or custom)
   - Update `LocateRequest` entity
   - Trigger shadow order placement

