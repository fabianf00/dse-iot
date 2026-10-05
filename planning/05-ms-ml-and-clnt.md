# 05 – Microservice ML and Client CLNT ML

## Part A – MS ML

### A1. Responsibilities

- Subscribe to **all** Fog instances over UDP and process the aggregated data for every data type.
- **Anomaly detection** with behaviour chains P ↦ C ↦ F: three different chains.
- **Prediction** with histograms. The bin width comes from the Freedman–Diaconis rule, is
  calculated upfront and set in config.
- Updating data and running the algorithms is **multi-threaded with a job/worker design**.
- Provide a **synchronous Java RMI API** for the standalone client.

### A2. Config `config/ml-1.json`

The shipped config (`implementation/config/ml-1.json`) contains:

- `fogNodes`: `fog-temp` (127.0.0.1:50201) and `fog-co2` (127.0.0.1:50202). The `id` must be the fog's `serviceId`.
- `udp`: `callbackPort` 50301, `leaseMs` 30 000 (renewed at half time). Optional: `bindHost`,
  `callbackHost` (for the VPN), `subscribeRetryMaxMs`.
- `workers`: 4 threads, queue capacity 1 000. `timeSeriesRetentionSeconds`: 3 600 (must cover
  the longest chain).
- `anomaly`: `minSamplesPerPhase` 2 and three chains (demo windows in seconds, fog window 10 s):

| Chain | Past | Current | Future (expected) |
|---|---|---|---|
| `temp-sudden-drop` | temperature > 20 °C, 120–60 s ago | temperature > 20 °C, last 60 s | temperature > 20 °C, next 60 s |
| `co2-empty-room` | co2 < 700 ppm, 120–60 s ago | co2 < 700 ppm, last 60 s | co2 < 700 ppm, next 60 s |
| `ventilation-effect` | co2 > 1000 ppm, 120–60 s ago | temperature < 19 °C, last 60 s | co2 < 1000 ppm, next 120 s |

  The CO2 threshold is 700 ppm instead of the first draft's 600, because the MAX aggregate of the
  normal CO2 signal reaches ~575 ppm. The realistic version from the assignment (10–20 min /
  0–10 min / next 0–10 min) maps to `fromSec: 1200, toSec: 600`, and so on. The third chain spans
  **two data types**.
- `prediction`: temperature bin width **0.41 °C**, CO2 bin width **11.6 ppm**, 500 samples each.
- `events.retention` 10 000; `rmi`: hostname 127.0.0.1, registry 1099, object port 50302, name `MlService`.

**Bin widths (Freedman–Diaconis, computed upfront).** One hour of fog aggregates was simulated
from the shipped IoT configs, with the real generators including anomaly injection, aggregated
like the fogs do (10 s windows, AVG for temperature, MAX for CO2, 360 values each).
`FreedmanDiaconis.binWidth` (`h = 2·IQR·n^(−1/3)`, quartiles with linear interpolation) gave
0.4120 °C and 11.6184 ppm, hardcoded as 0.41 and 11.6.

The first calculation gave 247 ppm for CO2. With the first-draft anomaly probability (0.5 % per
reading), an injected anomaly was active about a third of the time, and the MAX aggregate made
CO2 "anomalous" just as often. The probability was lowered to 0.15 % per reading (about one
anomaly per device every 11 minutes), which makes anomalies rare again.

### A3. Algorithms

**Behaviour chain semantics:**

- A *phase* `[fromSec, toSec]` holds at time `t` if the time series of its `dataType` has at least
  `minSamplesPerPhase` aggregates with *receive time* in `[t−fromSec, t−toSec]`, and **all** of
  them satisfy the condition. Receive time, not fog time, because hosts' clocks may differ.
- When P and C both hold at time `t0` and the chain has no open expectation, an
  `Expectation{chain, createdAt = t0, deadline = t0 + withinSec}` is created.
- Each evaluation checks the values of F's data type received in `(createdAt, min(now, deadline)]`:
  - a value violates the F condition → **Anomaly** (raised right away, expectation closed)
  - deadline passed and values were seen → fulfilled (debug log); a new expectation opens at once
    if P and C still hold
  - deadline passed without values → insufficient data (logged, not an anomaly)
- One open expectation per chain at a time. After an anomaly, the bad value lies in the current
  phase, so no new expectation opens until C holds again: one incident is reported once.
- Chains are evaluated after every new value of a data type they involve, and every second for
  all chains (so deadlines pass even without new data).

**Histogram prediction:**

- Bin index `i = floor((value − origin) / binWidth)`. Counts are kept over a sliding window of
  the last `maxSamples` values (the oldest value is removed from its bin when it leaves the
  window), so the prediction follows changes.
- Prediction = the bin with the highest count. Ties go to the bin closest to the latest value,
  then to the lower bin (deterministic).
- Output: `Prediction{dataType, unit, binLower, binUpper, mostLikelyValue = bin centre,
  confidence = count/total, sampleCount}`.
- A prediction change event is created only when the **winning bin changes**. That gives the
  client "changed predictions since the last poll". `getCurrentPredictions()` always returns
  the latest numbers.

### A4. Job / worker design (multi-threaded)

```
UDP receiver thread (FogClient)
   │ AGGREGATE → epoch/sequence check → MlJobs.submitIngest(aggregate, receivedAt)
   ▼
JobQueue (bounded ArrayBlockingQueue<Job>; full → job dropped and counted, never blocks)
   ▼
WorkerPool: N worker threads, loop { job = queue.take(); job.execute(); }
   │
   ├─ IngestAggregateJob    → TimeSeriesStore.append(...)
   │                          → submit UpdatePredictionJob (one per value)
   │                          → submit DetectAnomaliesJob  (coalesced per data type)
   ├─ DetectAnomaliesJob    → AnomalyDetector.evaluate(type, now) → EventLog.append(anomaly)
   ├─ UpdatePredictionJob   → HistogramPredictor.update(...)     → EventLog.append(change)
   └─ ExpectationTimeoutJob (submitted every 1 s, coalesced) → AnomalyDetector.evaluateAll(now)
```

- **Factory:** `MlJobs` creates and submits the jobs (Command objects).
- **Coalescing:** a detection job clears its "pending" flag when it *starts*, so values stored
  while it runs trigger one more run, and at most one detection per data type waits in the
  queue. Prediction jobs are **not** coalesced, because every value has to enter its histogram.
- **Concurrency:** `TimeSeriesStore` uses a `ConcurrentSkipListMap` per type. `HistogramPredictor`
  has a lock per data type and `AnomalyDetector` a lock per chain, so different types and chains
  run in parallel. `EventLog` is synchronized (low rate).
- **No cycles:** detector and predictor return their results; the jobs write them to the
  `EventLog`.
- **Failure isolation:** a worker catches `RuntimeException` per job, logs it, and continues.

### A5. UML class diagram (→ `uml/ms_ml.puml`)

Packages: `msml` (service, config), `msml.ingest`, `msml.jobs`, `msml.data`, `msml.anomaly`,
`msml.prediction`, `msml.events`, `msml.rmi`. Each package owns its config records, which are
bound directly from JSON.

```plantuml
@startuml ms_ml
package at.ac.univie.dse.iot.msml {
  class MlApplication { +{static} main(args : String[]) : void }
  class MlService extends AbstractMicroService {
    -store : TimeSeriesStore
    -events : EventLog
    -predictor : HistogramPredictor
    -queue : JobQueue
    -workers : WorkerPool
    -jobs : MlJobs
    -fogClient : FogClient
    -rmiServer : MlRmiServer
    ~status() : ServiceStatusDto
  }
  class MlConfig <<record>>
  package ingest {
    class FogClient {
      +start() : void
      +close() : void
      +statusSnapshot() : Map<String, String>
      ~tick() : void
      ~handle(m : FogMessage, source : InetSocketAddress) : void
    }
    interface AggregateListener { +onAggregate(fogId : String, a : AggregatedValue, receivedAt : Instant) : void }
    class FogSubscriptionState { status : SubscriptionStatus
      subscriptionId : String
      fogEpoch : long
      lastSequence : long
      leaseExpiresAt : long }
    enum SubscriptionStatus { PENDING
      ACTIVE
      EXPIRED }
    class FogEndpoint <<record>>
    class FogClientConfig <<record>>
  }
  package jobs {
    interface Job { +execute() : void
      +name() : String }
    class JobQueue { +submit(job : Job) : boolean
      ~take() : Job
      +size() : int
      +droppedCount() : long }
    class WorkerPool { +start() : void
      +stop(timeout : Duration) : void }
    class MlJobs { +submitIngest(a : AggregatedValue, at : Instant) : boolean
      +submitExpectationCheck() : boolean }
    class IngestAggregateJob <<record>> implements Job
    class DetectAnomaliesJob <<record>> implements Job
    class UpdatePredictionJob <<record>> implements Job
    class ExpectationTimeoutJob <<record>> implements Job
  }
  package data {
    class TimeSeriesStore { +append(t : DataType, u : Unit, at : Instant, v : double) : void
      +range(t : DataType, from : Instant, to : Instant) : List<DataPoint>
      +latest(t : DataType) : Optional<DataPoint>
      +unitOf(t : DataType) : Optional<Unit> }
    class DataPoint <<record>> { at : Instant
      value : double }
  }
  package anomaly {
    class AnomalyDetector { +evaluate(t : DataType, now : Instant) : List<Anomaly>
      +evaluateAll(now : Instant) : List<Anomaly>
      +openExpectation(chainId : String) : Optional<Expectation> }
    class BehaviourChain <<record>> { id : String
      description : String
      past : Phase
      current : Phase
      future : FuturePhase }
    class Phase <<record>> { dataType : DataType
      condition : ThresholdCondition
      fromSec : long
      toSec : long }
    class FuturePhase <<record>> { dataType : DataType
      condition : ThresholdCondition
      withinSec : long }
    interface Condition { +test(v : double) : boolean
      +describe(t : DataType) : String }
    class ThresholdCondition <<record>> implements Condition { op : Operator
      value : double }
    class Expectation <<record>>
    class PhaseObservation <<record>>
    class Anomaly <<record>>
  }
  package prediction {
    class HistogramPredictor { +update(t : DataType, u : Unit, v : double) : Optional<PredictionChange>
      +current(t : DataType) : Optional<Prediction>
      +currentAll() : List<Prediction> }
    class Histogram { +add(v : double) : void
      +mode(tieBreak : double) : Optional<Bin> }
    class Prediction <<record>>
    class PredictionChange <<record>>
    class FreedmanDiaconis { +{static} binWidth(samples : double[]) : double }
  }
  package events {
    class EventLog { +append(a : Anomaly) : AnomalyEvent
      +append(c : PredictionChange) : PredictionChangedEvent
      +since(cursor : long, max : int) : Page
      +epoch() : long }
    interface MlEvent <<sealed>>
  }
  package rmi {
    class MlRmiServer { +start() : void
      +stop() : void }
    class MlRemoteServiceImpl implements MlRemoteService
    class DtoMapper
  }
  MlService *-- FogClient
  MlService *-- WorkerPool
  MlService *-- MlRmiServer
  FogClient --> AggregateListener
  FogClient o-- FogSubscriptionState
  WorkerPool --> JobQueue
  MlJobs --> JobQueue
  MlJobs ..> Job : creates
  MlJobs --> TimeSeriesStore
  MlJobs --> AnomalyDetector
  MlJobs --> HistogramPredictor
  MlJobs --> EventLog
  AnomalyDetector --> TimeSeriesStore
  AnomalyDetector o-- BehaviourChain
  HistogramPredictor o-- Histogram
  MlRemoteServiceImpl --> EventLog
  MlRemoteServiceImpl --> HistogramPredictor
  MlRemoteServiceImpl ..> DtoMapper
  MlRmiServer --> MlRemoteServiceImpl
}
@enduml
```

### A6. Client API: Java RMI "ML Service API v1" (module `ml_api`)

- **Technology:** Java RMI, synchronous request/response.
- **Endpoint:** `rmi://<rmi.hostname>:1099/MlService`. The remote object is exported on fixed
  port 50302. Server sets `-Djava.rmi.server.hostname=<rmi.hostname>`.

```java
public interface MlRemoteService extends Remote {
    /** Events (anomalies + prediction changes) with sequence > afterSequence, oldest first. */
    PollResult pollEvents(long afterSequence, int maxEvents) throws RemoteException;

    /** Current prediction for each data type (snapshot, not incremental). */
    List<PredictionDto> getCurrentPredictions() throws RemoteException;

    /** Health/meta information. */
    ServiceStatusDto getStatus() throws RemoteException;
}
```

| DTO (immutable records, `Serializable`, `serialVersionUID = 1L`) | Fields |
|---|---|
| `PollResult` | `serverEpoch : long`, `events : List<MlEventDto>`, `nextCursor : long`, `truncated : boolean` (events after the cursor were evicted from retention), `hasMore : boolean` |
| `MlEventDto` (sealed interface) | `sequence : long`, `occurredAt : Instant`, `dataType : String` |
| `AnomalyDto` | + `chainId`, `chainDescription`, `past : PhaseObservationDto`, `current : PhaseObservationDto`, `expected : String` (e.g. "temperature > 20.0"), `observedValue : double`, `unit : String`, `expectedUntil : Instant` |
| `PhaseObservationDto` | `condition : String`, `from : Instant`, `to : Instant`, `samples : int` |
| `PredictionChangeDto` | + `previous : PredictionDto` (null for the first prediction), `current : PredictionDto` |
| `PredictionDto` | `dataType`, `binLower`, `binUpper`, `mostLikelyValue`, `confidence`, `sampleCount`, `unit` |
| `ServiceStatusDto` | `serviceId`, `serverEpoch`, `startedAt`, `dataTypes`, `fogStatus : Map<String, String>`, `queueSize`, `droppedJobs`, `lastEventSequence` |

**Contract:**

- The client starts with `afterSequence = 0`. Each call returns events where
  `sequence > afterSequence`, at most `maxEvents` (server caps it at 500). The client stores
  `nextCursor`.
- If `serverEpoch` differs from the one the client stored, the ML service restarted. The client
  resets its cursor to 0 and prints a notice.
- `truncated = true` → the client fell behind the retention. It prints "N events skipped".
- Errors: `RemoteException` (network, server down). `IllegalArgumentException` (wrapped by RMI)
  for `maxEvents ≤ 0` or `afterSequence < 0`.

**Example (logical) response:**

```
PollResult{ serverEpoch=1637412000000, nextCursor=17, truncated=false, hasMore=false,
  events=[
    AnomalyDto{ seq=16, at=2021-11-20T14:03:05Z, dataType=temperature, chainId=temp-sudden-drop,
                expected="temperature > 20.0 CELSIUS", observedValue=14.2 },
    PredictionChangeDto{ seq=17, dataType=co2,
                previous={640–680 ppm, 0.31}, current={680–720 ppm, 0.34} } ] }
```

### A7. Messages & communication (ML)

- **Inbound:** Fog Aggregate API (UDP), as the client side (see 04-ms-fog.md §4.2). ML subscribes
  to every configured Fog with `subscriberId = serviceId` and `callbackPort = 50301`.
- **Outbound:** RMI API (A6). ML does **not** use the CF, which is a CF-independent aspect.
- **Error case (novel):** *a duplicate or replayed AGGREGATE after a Fog restart.* Without
  `fogEpoch`, an old datagram that arrives late with `sequence = 57` after the restart (new
  sequence 1…) would be ingested twice or the new stream would be discarded. ML keys the
  dedupe by `(fogId, fogEpoch, sequence)`. A new epoch resets the sequence tracking. A
  datagram from an older epoch is dropped.

### A8. Deployment

`java -Djava.rmi.server.hostname=127.0.0.1 -jar ms_ml.jar config/ml-1.json`. Reachable on UDP
50301 (callbacks only), RMI 1099 and 50302. Depends on the Fog nodes for data, and works with
partial data.

---

## Part B – CLNT ML (standalone CLI)

### B1. Responsibilities

- Connect to one or more ML instances through RMI and **poll in a configurable interval**.
- Print **anomalies and prediction changes since the last poll** to the console.
- Standalone application: its own module and JAR, only depends on `ml_api` + `common`.

### B2. Config / CLI

`implementation/config/clnt-ml.json`:

```json
{
  "pollIntervalMs": 5000,
  "maxEventsPerPoll": 100,
  "endpoints": [ { "name": "ml-1", "host": "127.0.0.1", "registryPort": 1099, "bindName": "MlService" } ]
}
```

Only `endpoints` (with `name` and `host`) is required. Optional: `serviceId` (`clnt-ml`),
`pollIntervalMs` (5 000), `maxEventsPerPoll` (100, at most 500), `connectTimeoutMs` (3 000),
`reconnectBackoffMaxMs` (30 000), and per endpoint `registryPort` (1099) and `bindName` (`MlService`).

```
java -jar clnt_ml.jar <config.json> [--interval <ms>] [--once | --status] [--cursor-file <path>]
  --interval <ms>        poll interval, overrides pollIntervalMs
  --once                 poll every ML instance once, print new events and exit
  --status               print the status of every ML instance (fog subscriptions, queue, events) and exit
  --cursor-file <path>   remember what was printed, so the next run continues there
```

Exit codes: 0 ok, 1 an endpoint was unreachable (`--once`, `--status`) or the client failed, 2 wrong
arguments, 3 invalid config. Without `--cursor-file` the first poll prints the server's whole
retained history.

### B3. Design

- `MlClientApplication`: parses the arguments (`ClientArguments`), loads the config, installs RMI
  timeouts, and runs `MlClient` continuously or once.
- `MlClient extends AbstractMicroService<ClientConfig>`: builds one `EndpointPoller` per endpoint.
  Continuous mode uses the shared lifecycle (`run(config)` with shutdown hook); `runOnce` polls once.
  It loads the cursors from the cursor file at start and saves them at the end.
- `MlEndpointConnection`: looks up the stub lazily (`StubLocator`, by default the endpoint's RMI
  registry) and keeps it. After a failure it drops the stub and waits with exponential backoff
  (1 s, 2 s, 4 s … up to `reconnectBackoffMaxMs`); while waiting, polls are skipped silently.
- `EndpointPoller`: one poll of one endpoint. It fetches pages until `hasMore` is false (at most 20
  per poll). On a changed `serverEpoch` it prints "server restarted", resets the cursor and fetches
  from 0. If `truncated` is set it warns which events were missed. It prints "no new …" when there
  was nothing.
- `PollCursor`: per endpoint `{serverEpoch, position}`. `CursorStore`: the cursor file (JSON,
  written atomically).
- `PollingScheduler`: one fixed-delay task per endpoint, so a slow endpoint does not delay the others.
- `ConsoleRenderer` + `EventFormatter` (Strategy: `AnomalyFormatter`, `PredictionChangeFormatter`):
  one line per event (event time) or status message (current time). Lines are never interleaved.
- `TimeoutRmiSocketFactory`: a connect timeout for all RMI sockets, plus a 10 s response timeout,
  so an unreachable host (VPN down) cannot block the client for the OS TCP timeout.

```plantuml
@startuml clnt_ml
package at.ac.univie.dse.iot.clntml {
  class MlClientApplication { +{static} main(args : String[]) : void
    ~{static} run(args : String[], out : PrintStream, err : PrintStream) : int }
  class ClientArguments <<record>> { configFile : Path
    pollIntervalMs : Long
    once : boolean
    cursorFile : Path
    +{static} parse(args : String[]) : ClientArguments }
  class ClientConfig <<record>> { serviceId : String
    pollIntervalMs : long
    maxEventsPerPoll : int
    connectTimeoutMs : long
    reconnectBackoffMaxMs : long
    endpoints : List<EndpointConfig> }
  class MlClient extends AbstractMicroService {
    +runOnce(config : ClientConfig) : int
  }
  class PollingScheduler { +start(interval : Duration, threads : ThreadFactory) : void
    +pollAllOnce() : boolean
    +stop() : void }
  class EndpointPoller { +pollOnce() : boolean }
  class MlEndpointConnection { +service() : Optional<MlRemoteService>
    +failed() : long
    +succeeded() : boolean }
  interface StubLocator { +locate(e : EndpointConfig) : MlRemoteService }
  class PollCursor { +position() : long
    +adoptEpoch(epoch : long) : boolean
    +advanceTo(cursor : long) : void
    +snapshot() : Snapshot }
  class CursorStore { +load() : Map<String, Snapshot>
    +save(cursors : Map<String, Snapshot>) : void }
  interface EventFormatter<T> { +kind() : String
    +format(e : T) : String }
  class AnomalyFormatter implements EventFormatter
  class PredictionChangeFormatter implements EventFormatter
  class ConsoleRenderer { +events(endpoint : String, e : List<MlEventDto>) : void
    +nothingNew(endpoint : String) : void
    +info(endpoint : String, msg : String) : void
    +warn(endpoint : String, msg : String) : void }
  class TimeoutRmiSocketFactory extends RMISocketFactory
  MlClientApplication ..> ClientArguments
  MlClientApplication --> MlClient
  MlClient *-- PollingScheduler
  MlClient --> CursorStore
  PollingScheduler o-- EndpointPoller
  EndpointPoller --> MlEndpointConnection
  EndpointPoller --> PollCursor
  EndpointPoller --> ConsoleRenderer
  MlEndpointConnection --> StubLocator
  MlEndpointConnection --> MlRemoteService
  ConsoleRenderer o-- EventFormatter
}
@enduml
```

### B4. Console output

From a real run of all services; the last three lines show what an ML restart looks like
(as in `MlClientIntegrationTest`).

```
Polling 1 ML instance(s) every 10000 ms, Ctrl+C to stop
[10:28:52] ml-1  INFO        connected to rmi://127.0.0.1:1099/MlService
[10:29:01] ml-1  PREDICTION  co2                533.6–545.2 ppm (first prediction), confidence 100 %, n=1
[10:29:11] ml-1  PREDICTION  temperature        22.96–23.37 °C (was 22.55–22.96 °C), confidence 50 %, n=2
[10:29:23] ml-1  -- no new anomalies or prediction changes --
[10:32:11] ml-1  ANOMALY     co2-empty-room     expected co2 < 700.0 ppm, observed 1452.46 ppm
[10:32:11] ml-1  ANOMALY     temp-sudden-drop   expected temperature > 20.0 °C, observed 18.86 °C
[10:32:33] ml-1  WARN        unreachable (Connection refused), retry in 1 s
[10:32:43] ml-1  INFO        server restarted (new epoch), cursor reset
[10:32:43] ml-1  INFO        reachable again at rmi://127.0.0.1:1099/MlService
```
