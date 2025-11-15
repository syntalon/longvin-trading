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

- The backend ships with QuickFIX/J 2.3.1 dependencies. When `trading.fix.enabled=true`, `FixSessionManager` starts **both** a drop-copy acceptor (listens for DAS to connect) and one or more order-entry initiator sessions (the app connects out to DAS to place child account orders).
- The combined FIX settings live in `backend/src/main/resources/fix/das-mirror-trading.cfg`. The `[SESSION]` block with `ConnectionType=acceptor` defines the listening port/IDs for the drop-copy feed (`SocketAcceptPort`, `SenderCompID`, `TargetCompID`). Additional `[SESSION]` entries with `ConnectionType=initiator` define each child order-entry session (`SocketConnectHost`, `SocketConnectPort`, etc.). Update these per the credentials DAS provides.
- Configure the primary DAS account and the list of shadow (child) sessions in `backend/src/main/resources/application.properties` by adjusting `trading.fix.primary-session`, `trading.fix.primary-account`, `trading.fix.shadow-sessions`, and optional per-session overrides under `trading.fix.shadow-accounts.*`.
- Execution reports received on the drop-copy acceptor drive the `MirrorTradingApplication`. When `ExecType=New`, the backend synthesizes a mirror `NewOrderSingle` for each logged-on shadow session; when `ExecType=Replaced` or `ExecType=Canceled`, the service issues the corresponding `OrderCancelReplaceRequest`/`OrderCancelRequest` to keep secondary accounts synchronized.
- Mirrored orders inherit pricing, stop levels, and time-in-force from the drop-copy feed. Use `trading.fix.cl-ord-id-prefix` to control the generated `ClOrdID` prefix and per-session account overrides to route child orders correctly.
- Extend `MirrorTradingApplication` to support additional execution types or downstream processing (e.g., persistence, analytics) as your mirror-trading workflow evolves.
