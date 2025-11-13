# Longvin Trading

Multi-module project that bundles a Spring Boot backend together with an Angular frontend into a single runnable JAR.

## Project layout

- `pom.xml` – Maven parent that orchestrates all modules
- `backend/` – Spring Boot application serving the REST API and static frontend assets
- `ui/` – Angular application compiled and copied into the backend module during the Maven build

## Prerequisites

- Java 17+
- Maven Wrapper (included) or Maven 3.9+
- Internet access for dependency resolution (both Maven and npm packages)

## Build

```bash
./mvnw clean package
```

The command will:

- Install a local Node.js toolchain inside `ui/` (via `frontend-maven-plugin`)
- Build the Angular application (`npm run build`)
- Copy the Angular build output to `backend/target/classes/static`
- Produce `backend/target/backend-0.0.1-SNAPSHOT.jar` with UI assets embedded

## Run

```bash
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Then open <http://localhost:8080> to view the Angular UI.

## Development

- Frontend development server:

  ```bash
  cd ui
  npm install
  npm start
  ```

  Access the live reload UI at <http://localhost:4200>. If you need API calls to the Spring Boot backend, configure proxies as required.

- Backend development:

  ```bash
  ./mvnw spring-boot:run -pl backend
  ```

## FIX Integration Scaffolding

- The backend ships with QuickFIX/J 2.3.1 dependencies and a lifecycle-managed initiator that starts automatically when `trading.fix.enabled=true`.
- Default configuration lives in `backend/src/main/resources/fix/das-mirror-trading.cfg`; update host, ports, and credentials (`SenderCompID`, `TargetCompID`) with values from DAS Trader Pro.
- Configure the primary DAS account and one or more secondary (shadow) accounts in `backend/src/main/resources/application.properties` by adjusting `trading.fix.primary-session`, `trading.fix.shadow-sessions`, and optional per-session account overrides under `trading.fix.shadow-accounts.*`.
- NewOrderSingle messages received from the primary session are mirrored in real time to each logged-on shadow session. Mirrored orders receive a generated `ClOrdID` prefixed by `trading.fix.cl-ord-id-prefix` and can optionally override the `Account(1)` tag per shadow session.
- Extend `MirrorTradingApplication` to support cancels, replaces, or additional business logic as your mirror-trading workflow evolves.
