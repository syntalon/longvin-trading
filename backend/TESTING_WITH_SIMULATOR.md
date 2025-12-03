# Testing with Simulator

This guide explains how to test the trading application against the FIX simulator while keeping production configuration intact.

## Configuration Overview

The application uses Spring profiles to switch between test and production configurations:

- **Production (default)**: Uses `das-mirror-trading.cfg` pointing to production servers
- **Test**: Uses `das-mirror-trading-test.cfg` pointing to localhost simulator

## Quick Start

### 1. Start the Simulator

```bash
# From project root
mvn spring-boot:run -pl simulator

# Or from simulator directory
cd simulator
mvn spring-boot:run
```

The simulator will:
- Start an acceptor on port 8661 (for your initiator)
- Start an initiator that connects to localhost:9877 (your acceptor)

### 2. Start the Trading App in Test Mode

```bash
# From project root
mvn spring-boot:run -pl backend -Dspring-boot.run.profiles=test

# Or with JAR
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=test
```

### 3. Verify Connections

You should see:
- Trading app initiator (OS111->OPAL) connects to simulator's acceptor on port 8661
- Simulator's initiator (DAST->OS111) connects to trading app's acceptor on port 9877

## Configuration Files

All profiles are managed in a single YAML file: `src/main/resources/application.yml`

### Production Config (default profile)
- **FIX Config File**: `src/main/resources/fix/das-mirror-trading.cfg`
- **Initiator**: Connects to `4.2.100.90:8661` (OPAL production)
- **Acceptor**: Listens on `173.69.33.84:9877` (for DAS Trader)
- **Logging**: INFO level

### Test Config (test profile)
- **FIX Config File**: `src/main/resources/fix/das-mirror-trading-test.cfg`
- **Initiator**: Connects to `localhost:8661` (simulator)
- **Acceptor**: Listens on `0.0.0.0:9877` (for simulator to connect)
- **Logging**: DEBUG level

## Running Tests

### Test Scenario 1: Test Initiator Session

1. Start simulator first
2. Start trading app with `--spring.profiles.active=test`
3. Your initiator (OS111->OPAL) will connect to simulator
4. Send orders - simulator will respond with ExecutionReports

### Test Scenario 2: Test Acceptor Session

1. Start trading app with `--spring.profiles.active=test` first
2. Start simulator
3. Simulator's initiator (DAST->OS111) will connect to your acceptor
4. Simulator will send ExecutionReports when it receives orders

## Switching Between Test and Production

### Using Maven

**Test mode:**
```bash
mvn spring-boot:run -pl backend -Dspring-boot.run.profiles=test
```

**Production mode (default):**
```bash
mvn spring-boot:run -pl backend
# Or explicitly:
mvn spring-boot:run -pl backend -Dspring-boot.run.profiles=prod
```

### Using JAR

**Test mode:**
```bash
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=test
```

**Production mode:**
```bash
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
# Or explicitly:
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### Using Environment Variable

```bash
export SPRING_PROFILES_ACTIVE=test
mvn spring-boot:run -pl backend
```

## Important Notes

1. **Production config is never modified** - test config is completely separate
2. **Sequence numbers are separate** - test and prod use different FileStore directories
3. **Ports can conflict** - make sure simulator and trading app aren't both trying to use the same ports
4. **Profile selection** - If no profile is specified, production config is used by default

## Troubleshooting

### Port Already in Use
If you see "Address already in use" errors:
- Check if simulator is already running: `lsof -i :8661` or `lsof -i :9877`
- Make sure only one instance of each is running

### Connection Refused
- Verify simulator is running before starting trading app (for initiator test)
- Verify trading app acceptor is running before starting simulator (for acceptor test)

### Wrong Configuration Loaded
- Check logs for which config file is being loaded
- Verify `--spring.profiles.active=test` is set correctly
- Check `application-test.properties` exists and has correct config-path

