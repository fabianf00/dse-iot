# 01 – Architecture, Module Layout, Network & Deployment

## 1. Technology stack

| Concern | Choice | Rationale |
|---|---|---|
| Language | Java 17 (LTS) | RMI is required. One language for all modules makes code reuse possible |
| Build | Maven multi-module (parent POM in `implementation/`) | Shared dependency management, `mvn package` builds every service |
| Serialization (CF + UDP) | JSON (Jackson) | Readable, language-neutral (helps third-party developers), easy to debug with `nc` or Wireshark |
| Serialization (RMI) | Java serialization of immutable DTOs | Built into RMI |
| Logging | SLF4J + Logback | Per-service log files, consistent format |
| Tests | JUnit 5, Mockito, Awaitility (async assertions) | Unit tests now, integration tests in FINAL |
| UML | PlantUML (`.puml` next to the code) | Versioned and diffable, kept next to the code |
| Packaging | Fat JAR per service (maven-shade) + start scripts | `java -jar ms_iot.jar config/iot-temp-1.json` |

Base package: `at.ac.univie.dse.iot` (adapt if the course prescribes another).

## 2. Module layout

```
implementation/
│   ├── pom.xml                 # parent POM
│   ├── common/                 # config loading, JSON codec, AbstractMicroService, shared DTOs
│   ├── cf/                     # Communication Framework (library)
│   ├── ms_iot/                 # IoT device simulator (uses cf)
│   ├── ms_fog/                 # Fog aggregator (uses cf, UDP server)
│   ├── ml_api/                 # RMI remote interface + DTOs (shared by ms_ml and clnt_ml)
│   ├── ms_ml/                  # ML service (UDP client, workers, RMI server)
│   ├── clnt_ml/                # CLI client (RMI)
│   ├── config/                 # one JSON config per instance (SIMI)
│   │   ├── iot-temp-1.json … iot-temp-3.json
│   │   ├── iot-co2-1.json  … iot-co2-3.json
│   │   ├── fog-temp.json, fog-co2.json
│   │   ├── ml-1.json
│   │   └── clnt-ml.json
│   ├── scripts/                # start-all.sh, stop-all.sh, start-<service>.sh
│   └── uml/                    # *.puml sources + rendered PNGs
```

Module dependencies (no cycles):

```
common  ◄── cf ◄── ms_iot
   ▲         ▲
   │         └──── ms_fog
   ├── ml_api ◄── ms_ml
   │        ▲
   │        └──── clnt_ml
   └──────────── (all services)
```

`ms_ml` does **not** depend on `cf`. It only uses UDP and RMI, as the assignment's layout
shows.

## 3. Shared design (`common`)

The assignment points out that framework-style reuse saves time. These classes are shared:

- `AbstractMicroService<C extends ServiceConfig>` (**Template Method**). `run()` calls
  `loadConfig()`, `initialize()`, `startComponents()`, registers a shutdown hook, and waits.
  Subclasses implement only the hooks. Gives every service the same lifecycle and logging.
- `ConfigLoader` turns JSON into typed config records and validates them (fail fast at startup,
  with clear error messages).
- `JsonCodec` wraps a single configured Jackson `ObjectMapper`.
- DTOs (immutable records): `SensorReading`, `AggregatedValue`, `DataType` (a string value
  object, not an enum, so third parties can add sensor types through config only), `Unit`,
  `AggregationMode` (shared because both Fog and ML need it), and `Topics` (CF topic names).
- `common.fogapi`: messages (`FogMessage`) and codec of the UDP Fog Aggregate API, shared by
  MS Fog (server) and MS ML (client).
- `NamedThreadFactory`, `ExecutorUtils.shutdownGracefully(...)`.

Patterns used across the project:

| Pattern | Where |
|---|---|
| Facade | `CommunicationFramework` hides sockets, scanning and routing from the MSs |
| Observer | `MessageListener` subscriptions in the CF, UDP aggregate callbacks in ML |
| Strategy | `ScanStrategy` (CF), `ValueGenerator` (IoT), `AggregationStrategy` (Fog), `Condition` (ML) |
| Decorator | `AnomalyInjectingGenerator` wraps any `ValueGenerator` |
| Factory / Builder | `CommunicationFrameworkFactory`, `AggregationStrategyFactory`, `CfConfig.builder()` |
| Command + Worker Pool | ML jobs (`Job`, `JobQueue`, `WorkerPool`) |
| Template Method | `AbstractMicroService` |
| Remote Facade / DTO | `MlRemoteService` + serializable DTOs |

## 4. Network layout

### 4.1 Instances, addresses and ports (local deployment, one host)

| Instance | Role | Host | CF TCP | UDP | RMI |
|---|---|---|---|---|---|
| iot-temp-1 / -2 / -3 | MS IoT (temperature) | 127.0.0.1 | 50101 / 50102 / 50103 | – | – |
| iot-co2-1 / -2 / -3 | MS IoT (CO2) | 127.0.0.1 | 50111 / 50112 / 50113 | – | – |
| fog-temp | MS Fog (temperature, AVG, 10 s) | 127.0.0.1 | 50121 | 50201 (server) | – |
| fog-co2 | MS Fog (CO2, MAX, 10 s) | 127.0.0.1 | 50122 | 50202 (server) | – |
| ml-1 | MS ML | 127.0.0.1 | – | 50301 (callback receiver) | registry 1099, remote object 50302 |
| clnt-ml | CLNT ML | 127.0.0.1 | – | – | client only |

- **CF scan range:** hosts `[127.0.0.1]`, ports `50100–50129`. The range is small on purpose
  so random scanning finds peers within seconds. It is configurable.
- **RMI remote-object port is fixed** (50302) instead of random, so it works through the VPN and
  firewalls. `java.rmi.server.hostname` is set from the config.

### 4.2 VPN deployment

Instances are spread over several machines in the VPN. Only the host entries change:

- CF `scan.hosts` lists the VPN IPs of all machines, e.g. `["10.8.0.2","10.8.0.3","10.8.0.4","10.8.0.5"]`.
- ML `fogNodes[].host` points to the VPN IPs of the fog nodes.
- CLNT `mlEndpoints[].host` points to ML's VPN IP. ML sets `rmi.hostname` to its VPN IP.

### 4.3 Diagram

```
 Host 127.0.0.1 (or VPN 10.8.0.x)

 CF P2P overlay (TCP, ports 50100-50129, each peer: ≤3 outgoing, ∞ incoming)
   iot-temp-1:50101 ─┐        ┌─ iot-co2-1:50111
   iot-temp-2:50102 ─┼─ mesh ─┼─ iot-co2-2:50112
   iot-temp-3:50103 ─┘   │    └─ iot-co2-3:50113
                         │
             fog-temp:50121 (CF)          fog-co2:50122 (CF)
                  │ UDP :50201                 │ UDP :50202
                  │  ▲ SUBSCRIBE/RENEW         │  ▲
                  ▼  │ AGGREGATE               ▼  │
                       ml-1  UDP :50301 (callbacks)
                             RMI registry :1099, object :50302
                                   ▲
                                   │ RMI poll every 5 s
                               clnt-ml
```

## 5. Deployment

- **Build:** `cd implementation && ./mvnw clean package` (Maven Wrapper, no global Maven install needed) produces runnable JARs `ms_iot/target/ms_iot.jar`, `ms_fog/target/ms_fog.jar`, `ms_ml/target/ms_ml.jar`, `clnt_ml/target/clnt_ml.jar`.
- **Run:** `java -jar ms_iot/target/ms_iot.jar config/iot-temp-1.json`. One OS process per
  instance. SIMI means the same JAR runs with a different config.
- **Scripts** (`implementation/scripts/`, bash 3.2 compatible): `start-all.sh [--demo] [--build]`
  starts ML first, then the fogs, then the IoT devices, as background processes. The order is not
  required for correctness: every component retries and resubscribes. It only avoids startup log
  noise. Logs go to `run/logs/<instance>.log`, PIDs to `run/pids/<instance>.pid`.
  `stop-all.sh` stops everything (SIGTERM, so each service says goodbye to its peers; SIGKILL
  after 10 s). `start-service.sh` / `stop-service.sh` handle one instance, `status.sh` shows
  what runs, and `client.sh` runs CLNT ML. The client is started by hand because it is
  interactive. `--demo` uses `config/demo/<instance>.json` where it exists (two devices with
  seeds that inject an anomaly about 3 minutes after start).
- **Dependencies between MSs at runtime:**
  - IoT depends on nothing. It publishes into the overlay even with no peers (data is lost, which is acceptable for a stream).
  - Fog needs CF peers to receive data. Without subscribers it still aggregates.
  - ML needs the Fog UDP endpoints. It retries subscriptions until they are acknowledged.
  - CLNT needs ML's RMI registry. It retries with backoff.
- **Optional:** a `docker-compose.yml` with static IPs (`172.28.0.0/24`). This is a nice-to-have
  and is not required. The VPN setup is the priority.

## 6. Design decision log (multiple options per major decision)

For each major decision, several options were considered:

| # | Decision | Options considered | Choice and reason |
|---|---|---|---|
| D1 | CF wire format | Java serialization / JSON / Protobuf / custom binary | **JSON**: readable, language-neutral, no code generation. Size overhead is fine at our rates |
| D2 | TCP framing | newline-delimited / length-prefixed | **4-byte length prefix**: binary-safe, no escaping, and frames can be size-limited (DoS guard) |
| D3 | CF threading | thread per connection (blocking IO) / NIO selector | **Blocking IO, reader + writer thread per connection**: simple and fits the course. Peer count is small (≤ ~20) |
| D4 | Loop prevention | TTL only / TTL + seen-ID cache / spanning tree | **TTL (required) + seen-ID cache**: TTL alone still delivers duplicates through cycles |
| D5 | Peer refresh | drop all outgoing peers at once / rolling (oldest first) | **Rolling refresh**: avoids moments when a node has no peers, and still reshuffles the topology over time |
| D6 | Fog → ML | ML polls Fog over UDP / Fog pushes to subscribers (callback) | **Subscribe + push with leases**: matches "asynchronous callback based". Leases clean up dead subscribers |
| D7 | ML job design | one thread per data type / shared worker pool + job queue | **Bounded job queue + fixed worker pool with per-type coalescing**: scales, provides backpressure |
| D8 | CLNT "since last poll" | timestamp-based / server sequence cursor | **Monotonic sequence cursor + server epoch**: no clock-skew issues, and server restarts are detected |
| D9 | Data type modelling | enum / string value object | **String `DataType`**: new sensor types need config changes only (third-party friendly) |
