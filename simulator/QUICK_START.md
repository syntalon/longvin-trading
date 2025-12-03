# Quick Start Guide

## Building and Running

From the project root:
```bash
mvn clean package -pl simulator
java -jar simulator/target/simulator-0.0.1-SNAPSHOT.jar
```

Or from the simulator directory:
```bash
cd simulator
mvn clean package
java -jar target/simulator-0.0.1-SNAPSHOT.jar
```

Or with Maven:
```bash
mvn spring-boot:run -pl simulator
```

## Testing Your Application

### Test 1: Your Initiator Session (OS111->OPAL)

1. **Start the simulator first** (acceptor on port 8661)
2. **Start your main application** (initiator will connect to simulator)
3. **Send orders** from your application
4. **Simulator will respond** with ExecutionReports automatically

### Test 2: Your Acceptor Session (DAST->OS111)

1. **Start your main application first** (acceptor on port 9877)
2. **Start the simulator** (initiator will connect to your acceptor)
3. **Simulator acts as DAS Trader** and will send ExecutionReports when it receives orders

## Configuration

Edit `src/main/resources/application.properties` to change:
- Ports
- CompIDs
- Heartbeat intervals

## Logs

The simulator logs all FIX messages. Watch for:
- Session creation/logon/logout
- Incoming NewOrderSingle messages
- Outgoing ExecutionReport messages
- Sequence number synchronization

## Sequence Numbers

Sequence numbers are persisted in `target/quickfix/store/`. To reset:
```bash
rm -rf target/quickfix/store/*
```

