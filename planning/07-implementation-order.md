# 07 – Implementation Order

Build bottom-up along the module dependencies: `common` → `cf` → `ms_iot` / `ms_fog` →
`ml_api` → `ms_ml` → `clnt_ml`. Each step ends with its unit tests green and, where one exists,
its integration test from 06-integration-testing.md.

## Step 0 – Build setup

- Maven parent POM in `implementation/` with modules `common`, `cf`, `ms_iot`, `ms_fog`,
  `ml_api`, `ms_ml`, `clnt_ml`. Shared dependency versions (Jackson, SLF4J/Logback, JUnit 5,
  Mockito, Awaitility). Shade plugin for runnable JARs of the services.
- Empty `main` in every service module, so `mvn package` builds all JARs from the start.

## Step 1 – `common`

- `ConfigLoader` (JSON → records, validation with clear messages), `JsonCodec`.
- `AbstractMicroService<C>` lifecycle (load config, initialize, start, shutdown hook, stop).
- DTOs `DataType`, `Unit`, `SensorReading`, `AggregatedValue`.
- `NamedThreadFactory`, `ExecutorUtils.shutdownGracefully`.
- Tests: config validation errors, JSON round trip of the DTOs.

## Step 2 – `cf` (the critical path; see 02-communication-framework.md)

1. `CfMessage`, `MessageType`, `NodeId`, `FrameCodec` (length-prefixed JSON, max frame size).
2. `PeerConnection` with reader thread, writer thread and bounded outbound queue.
3. `HandshakeHandler` (HELLO / HELLO_ACK, self and duplicate-link rejection), `PeerAcceptor`.
4. `ConnectionManager` (≤ 3 outgoing, unlimited incoming).
5. `RandomRangeScanStrategy` + `PeerScanner` with candidate backoff.
6. `SeenMessageCache`, `MessageRouter` (TTL, dedupe, flood except sender).
7. `SubscriptionRegistry` with topic wildcards, `MessageDispatcher` on its own pool.
8. `HeartbeatMonitor` (PING/PONG, timeout), `PeerRefresher` (rolling refresh), graceful `BYE`.
9. `PeerNode` facade + `CommunicationFrameworkFactory`.

Tests: the unit tests in CF §9, then **IT-CF**.

## Step 3 – `ms_iot` (see 03-ms-iot.md)

1. `ValueGenerator` implementations (`Sine`, `RandomWalk`, `Constant`) + `ValueGeneratorFactory`.
2. `AnomalyInjectingGenerator` decorator (seeded, deterministic).
3. `SimulatedSensor` (sequence, `bootId`, range check), `SensorPublisher` (scheduled publish).
4. `IotDeviceService` + 6 config files (3 × temperature, 3 × CO2).

Tests: generator output ranges and determinism, then **IT-IoT**.

## Step 4 – `ms_fog` (see 04-ms-fog.md; can run in parallel with Step 3)

1. `AggregationStrategy` (`AVG`/`MAX`/`MIN`) + factory.
2. `AggregationWindow` + `SensorReadingCollector` with the atomic window swap.
3. `AggregationScheduler` (empty-window handling and warning).
4. `UdpMessageCodec`, `UdpEndpoint`, `SubscriptionManager` (leases, sweep, limits).
5. `AggregatePublisher` (sequence, `fogEpoch`).
6. `FogNodeService` + configs `fog-temp.json`, `fog-co2.json`.

Tests: strategies, window swap under concurrency, codec, lease expiry, then **IT-Fog**.

## Step 5 – `ml_api`

`MlRemoteService` interface and immutable `Serializable` DTOs (`PollResult`, `MlEventDto`,
`AnomalyDto`, `PredictionChangeDto`, `PredictionDto`, `ServiceStatusDto`). It comes early
because both `ms_ml` and `clnt_ml` depend on it.

## Step 6 – `ms_ml` (see 05-ms-ml-and-clnt.md Part A)

1. Job framework: `Job`, `JobQueue` (bounded), `Worker`, `WorkerPool`, `JobFactory` with per-type coalescing.
2. `TimeSeriesStore`.
3. `FogClient`: subscribe with retry and backoff, renew, epoch/sequence tracking, dedupe by `(fogId, fogEpoch, sequence)`.
4. Anomaly detection: `Condition`, `Phase`, `BehaviourChain`, `ChainLoader`, `Expectation`,
   `AnomalyDetector`, `ExpectationTimeoutJob`. Configure the 3 chains.
5. Prediction: `FreedmanDiaconis`, `Histogram` (sliding window, tie-break), `HistogramPredictor`.
   Calculate the bin widths from generated aggregate data and hardcode them in `ml-1.json`.
6. `EventLog` (sequence, epoch, retention).
7. `MlRemoteServiceImpl` + `MlRmiServer` (registry, fixed object port, hostname from config).

Tests: chain evaluation (fires, does not fire, insufficient data), histogram mode and change
detection, Freedman–Diaconis against a known sample, EventLog paging/truncation, then **IT-ML**.

## Step 7 – `clnt_ml` (see 05-ms-ml-and-clnt.md Part B)

1. `ClientConfig` + argument parsing (`--interval`, `--once`).
2. `MlEndpointConnection` (lookup, reconnect with backoff).
3. `PollCursor` (epoch change → reset), `PollingScheduler` (one task per endpoint, drains `hasMore`).
4. `ConsoleRenderer` + formatters.

Tests: cursor logic, formatting, then the **end-to-end** scenario.

## Step 8 – Deployment and full system run

- `scripts/start-all.sh` / `stop-all.sh` / `start-service.sh` / `stop-service.sh` / `status.sh` /
  `client.sh`, all instance configs in `config/`, demo overrides in `config/demo/`.
- `scripts/e2e-test.sh`: the end-to-end scenario of 06 against the real services (local).
- PlantUML diagrams in `uml/` (one per artifact, rendered PNGs). `scripts/check-uml.sh` checks
  them against the code (types in both directions, operation names).
- Open: the run over the VPN (only host entries in the configs change, see 01 §4.2).

## Technical risks

| Risk | Mitigation |
|---|---|
| RMI over VPN fails (wrong advertised hostname, random object port) | `java.rmi.server.hostname` from config, fixed object port 50302 |
| Random scanning too slow or finds no peers | Small, configurable port ranges, 500 ms scan interval, backoff list for failed candidates |
| Overlay partition (islands), so a fog gets no data | Rolling refresh, unlimited incoming links, TTL sized to the diameter, empty-window warning |
| Concurrency bugs (CF forwarding, Fog window, ML jobs) | Immutable messages, atomic window swap, per-type locks, bounded queues, load in integration tests |
| Behaviour chains never fire in demos | Deterministic anomaly injection in IoT, short windows in demo configs |

## Open technical questions

1. Is a behaviour chain allowed to span two data types (the third chain: CO2 → temperature → CO2)?
2. Are shortened time windows (seconds instead of minutes) fine for the demo configs?
3. Are third-party libraries (Jackson, SLF4J/Logback, JUnit, Mockito, Awaitility) allowed?
4. Which Java version runs on the test machines (11 or 17)?
5. Is skipping the sender when forwarding acceptable under "forward to all connected peers"?
