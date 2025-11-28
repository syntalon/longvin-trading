# FIX Simulator

A FIX protocol simulator for testing DAS Trader connections. This simulator can act as both:

1. **Acceptor** - Simulates OPAL server for your initiator session (OS111->OPAL)
2. **Initiator** - Simulates DAS Trader connecting to your acceptor session (DAST->OS111)

## Features

- Simulates both acceptor and initiator FIX sessions
- Automatically responds to NewOrderSingle messages with ExecutionReport
- Configurable via `application.properties`
- Uses FIX 4.2 protocol
- Persists sequence numbers using FileStore

## Configuration

Edit `src/main/resources/application.properties` to configure:

```properties
# Acceptor session (for your initiator to connect to)
simulator.acceptor.enabled=true
simulator.acceptor.port=8661
simulator.acceptor.sender-comp-id=OPAL
simulator.acceptor.target-comp-id=OS111

# Initiator session (simulating DAS Trader)
simulator.initiator.enabled=true
simulator.initiator.host=localhost
simulator.initiator.port=9877
simulator.initiator.sender-comp-id=DAST
simulator.initiator.target-comp-id=OS111
simulator.initiator.heartbeat-interval=30
```

## Running

### Build
From the project root:
```bash
mvn clean package -pl simulator
```

Or from the simulator directory:
```bash
cd simulator
mvn clean package
```

### Run
From the project root:
```bash
java -jar simulator/target/simulator-0.0.1-SNAPSHOT.jar
```

Or with Maven:
```bash
mvn spring-boot:run -pl simulator
```

Or from the simulator directory:
```bash
cd simulator
mvn spring-boot:run
```

## Testing

1. **Test your initiator session:**
   - Start the simulator
   - Start your main application
   - Your initiator (OS111->OPAL) should connect to the simulator's acceptor on port 8661
   - Send orders from your application - the simulator will respond with ExecutionReports

2. **Test your acceptor session:**
   - Start your main application first (acceptor on port 9877)
   - Start the simulator
   - The simulator's initiator (DAST->OS111) will connect to your acceptor
   - The simulator will send ExecutionReports when it receives orders

## Logs

The simulator logs all FIX messages and session events. Check the console output for:
- Session creation/logon/logout events
- Incoming and outgoing messages
- ExecutionReports sent in response to orders

## Sequence Numbers

The simulator uses FileStore to persist sequence numbers in `target/quickfix/store/`. To reset sequence numbers, delete these files.

