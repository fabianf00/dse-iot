# 04 – Microservice Fog

## 1. Responsibilities

- Receive sensor readings of **one** data type through the CF.
- Collect them for a configurable window, aggregate them (`AVG`, `MAX` or `MIN`), publish the
  result, and repeat.
- **SIMI:** one instance per data type. Configurable: aggregation mode, data type, window length.
- Offer **asynchronous, callback-based access to aggregates over UDP**. ML subscribes, and Fog
  pushes every aggregate to the subscriber's callback address.

## 2. Instances and config

| Instance | Data type | Mode | Window | CF port | UDP port |
|---|---|---|---|---|---|
| fog-temp | temperature | AVG | 10 000 ms | 50121 | 50201 |
| fog-co2 | co2 | MAX | 10 000 ms | 50122 | 50202 |

```json
{
  "serviceId": "fog-temp",
  "dataType": "temperature",
  "unit": "CELSIUS",
  "aggregationMode": "AVG",
  "windowMs": 10000,
  "udp": { "port": 50201 },
  "cf": {
    "listenPort": 50121,
    "scan": { "hosts": ["127.0.0.1"], "portRange": { "from": 50100, "to": 50129 } }
  }
}
```

Optional with defaults: `emptyWindowWarningAfter` (3), `udp.bindHost` (`0.0.0.0`),
`udp.maxLeaseMs` (120 000), `udp.maxSubscribers` (32), `cf.nodeId` (= `serviceId`). `unit` is
required: readings in another unit are dropped, and empty aggregates still carry a unit.

## 3. Design

Packages: `msfog` (service, config), `msfog.aggregation` (collector, windows, strategies,
scheduler), `msfog.udp` (UDP server, request handler, subscriptions, aggregate push). The
message types and codec of the UDP API live in `common.fogapi` (`FogMessage`,
`FogMessageCodec`), because MS ML is the client of the same protocol.

- `FogNodeService extends AbstractMicroService<FogConfig>`
- `SensorReadingCollector` implements `MessageListener` and is subscribed to `sensor.<dataType>`.
  It checks `dataType` and `unit` and adds readings to the current `AggregationWindow`.
  Thread-safety: the window is swapped atomically (`AtomicReference<AggregationWindow>`), and a
  closed window refuses further readings, so a reading that raced with the swap is retried on the
  new window (see §4.5).
- `AggregationScheduler` runs every `windowMs`. It swaps the window, aggregates the closed window,
  creates an `AggregatedValue` and hands it to `AggregatePublisher`.
- `AggregationStrategy` (**Strategy**) has `Avg`, `Max` and `Min` in `AggregationStrategies`,
  whose `forMode(aggregationMode)` is the factory. Adding `MEDIAN` is one new class.
- `UdpServer` is one `DatagramSocket` with a receiver thread for `SUBSCRIBE`/`RENEW`/`UNSUBSCRIBE`,
  plus `send`. `FogRequestHandler` turns one request datagram into one answer (testable without
  sockets). `FogMessageCodec` handles the JSON datagrams.
- `SubscriptionManager` keeps the subscriptions by `subscriberId` with leases. Expired leases are
  ignored right away and removed by a sweep every second.
- `AggregatePublisher` sends an `AGGREGATE` datagram to every active subscription and keeps a
  per-instance `sequence`.

**Windowing uses receive time**, not `measuredAt`. Device clocks are not synchronized (clock
skew).

### UML class diagram (draft → `uml/ms_fog.puml`)

```plantuml
@startuml ms_fog
package at.ac.univie.dse.iot.msfog {
  class FogNodeApplication { +{static} main(args : String[]) : void }
  class FogNodeService extends AbstractMicroService {
    -cf : CommunicationFramework
    -collector : SensorReadingCollector
    -scheduler : AggregationScheduler
    -udp : UdpEndpoint
  }
  class FogConfig <<record>> { serviceId : String
    dataType : DataType
    aggregationMode : AggregationMode
    windowMs : long
    udp : UdpConfig
    cf : CfConfig }
  enum AggregationMode <<common>> { AVG
    MAX
    MIN }
  class SensorReadingCollector implements MessageListener {
    -current : AtomicReference<AggregationWindow>
    +onMessage(m : CfMessage) : void
    +add(r : SensorReading) : void
    +closeWindow() : Snapshot
  }
  class AggregationWindow {
    -openedAt : long
    -values : List<Double>
    -devices : Set<String>
    -closed : boolean
    +add(r : SensorReading) : boolean
    +close(closedAt : long) : Snapshot
  }
  interface AggregationStrategy { +aggregate(values : List<Double>) : OptionalDouble
    +mode() : AggregationMode }
  class Avg implements AggregationStrategy
  class Max implements AggregationStrategy
  class Min implements AggregationStrategy
  class AggregationStrategies { +{static} forMode(m : AggregationMode) : AggregationStrategy }
  class AggregationScheduler {
    -strategy : AggregationStrategy
    -publisher : AggregatePublisher
    -consecutiveEmptyWindows : int
    +start(window : Duration, threads : ThreadFactory) : void
    +stop() : void
    ~aggregateAndPublish() : AggregatedValue
  }
  class AggregatePublisher implements Consumer<AggregatedValue> {
    -sequence : AtomicLong
    -fogEpoch : long
    +accept(v : AggregatedValue) : void
  }
  class UdpServer {
    -socket : DatagramSocket
    +start() : void
    +send(datagram : byte[], to : InetSocketAddress) : void
    +close() : void
  }
  class FogRequestHandler {
    +handle(data : byte[], length : int, source : InetSocketAddress) : FogMessage
  }
  class SubscriptionManager {
    -subscriptions : Map<String, FogSubscription>
    +subscribe(subscriberId : String, callback : InetSocketAddress, leaseMs : long) : FogSubscription
    +renew(id : String, leaseMs : long) : Optional<FogSubscription>
    +unsubscribe(id : String) : boolean
    +active() : Collection<FogSubscription>
    +sweepExpired() : void
  }
  class FogSubscription <<record>> { subscriptionId : String
    subscriberId : String
    callback : InetSocketAddress
    leaseExpiresAt : Instant }
  class FogMessageCodec <<common>> { +encode(m : FogMessage) : byte[]
    +decode(b : byte[], off : int, len : int) : FogMessage }

  FogNodeService *-- SensorReadingCollector
  FogNodeService *-- AggregationScheduler
  FogNodeService *-- UdpServer
  FogNodeService *-- SubscriptionManager
  SensorReadingCollector *-- AggregationWindow
  AggregationScheduler --> SensorReadingCollector
  AggregationScheduler --> AggregationStrategy
  AggregationScheduler --> AggregatePublisher
  AggregatePublisher --> SubscriptionManager
  AggregatePublisher --> UdpServer
  UdpServer --> FogRequestHandler
  FogRequestHandler --> SubscriptionManager
  FogRequestHandler --> FogMessageCodec
}
@enduml
```

## 4. Messages & communication

### 4.1 Through the CF (inbound only)

Subscribes to `sensor.<dataType>`. Payload `SensorReading` as described in 03-ms-iot.md.
Readings with an unexpected `unit` or `dataType` are dropped and logged (`WARN` with deviceId).

### 4.2 UDP aggregate API (independent of the CF): document as "Fog Aggregate API v1"

- **Transport:** UDP, one JSON object (UTF-8) per datagram, **≤ 1400 bytes** (fits in an
  Ethernet/VPN MTU without IP fragmentation).
- **Server:** `fog-<type>` on `udp.port` (e.g. `127.0.0.1:50201`).
- **Common fields:** `v` (1), `type`, `requestId` (UUID, echoed in responses so the client can
  match them).

| Type | Direction | Fields | Semantics |
|---|---|---|---|
| `SUBSCRIBE` | client → fog | `subscriberId`, `callbackPort`, `callbackHost?`, `leaseMs` | Idempotent per `subscriberId` (a repeat replaces the old one). Callback host defaults to the datagram's **source IP** |
| `SUBSCRIBE_ACK` | fog → client | `subscriptionId`, `dataType`, `unit`, `aggregationMode`, `windowMs`, `leaseExpiresAt`, `fogEpoch` | Lease is capped at `maxLeaseMs`. A repeated SUBSCRIBE returns the same `subscriptionId` |
| `RENEW` | client → fog | `subscriptionId`, `leaseMs` | Recommended at ½ lease |
| `RENEW_ACK` | fog → client | `subscriptionId`, `leaseExpiresAt` | |
| `UNSUBSCRIBE` | client → fog | `subscriptionId` | Best effort. The lease expires anyway |
| `UNSUBSCRIBE_ACK` | fog → client | `subscriptionId` | |
| `AGGREGATE` | fog → callback | see below | Fire-and-forget, one per window per subscriber |
| `ERROR` | fog → client | `code`, `message`, `requestId` | Codes: `MALFORMED_REQUEST`, `UNSUPPORTED_VERSION`, `UNKNOWN_SUBSCRIPTION`, `TOO_MANY_SUBSCRIBERS` |

**`AGGREGATE` example:**

```json
{
  "v": 1,
  "type": "AGGREGATE",
  "fogId": "fog-temp",
  "fogEpoch": 1637412000000,
  "sequence": 57,
  "dataType": "temperature",
  "unit": "CELSIUS",
  "aggregationMode": "AVG",
  "windowStart": 1637412180000,
  "windowEnd": 1637412190000,
  "sampleCount": 28,
  "deviceCount": 3,
  "value": 21.42
}
```

An empty window is still sent, with `sampleCount: 0` and `value: null`. It works as a heartbeat:
ML can tell "Fog alive but no sensor data" (CF partition) apart from "Fog dead" (no datagrams).

### 4.3 Sequences

```
ML                                   fog-temp (UDP :50201)
 │── SUBSCRIBE{subscriberId:ml-1, callbackPort:50301, leaseMs:30000} ──►│
 │◄─ SUBSCRIBE_ACK{subscriptionId:s-1, windowMs:10000, fogEpoch:E} ─────│
 │◄─ AGGREGATE{seq:1 …}  (every 10 s) ──────────────────────────────────│
 │── RENEW{s-1} (every 15 s) ──────────────────────────────────────────►│
 │◄─ RENEW_ACK ─────────────────────────────────────────────────────────│
```

### 4.4 Error handling (good and bad cases)

| Case | Behaviour |
|---|---|
| SUBSCRIBE datagram lost | Client resends after 1 s, 2 s, 4 s … (capped at 30 s) until it gets an ACK. Safe, because SUBSCRIBE is idempotent |
| AGGREGATE lost or reordered | Client sees a gap/out-of-order `sequence`: logs the gap, drops stale datagrams (`sequence ≤ last`). No retransmission, because the stream is live |
| ML crashes | No more RENEW, lease expires, Fog stops sending. No endless sending to dead hosts |
| Fog restarts | `fogEpoch` changes and `sequence` restarts. Client RENEW gets `ERROR{UNKNOWN_SUBSCRIPTION}` and the client resubscribes |
| Malformed or oversized datagram | `ERROR{MALFORMED_REQUEST}` to the source, no state change |
| Subscriber flood | `maxSubscribers` → `ERROR{TOO_MANY_SUBSCRIBERS}` |

### 4.5 Edge case: window-boundary race

**Window-boundary race:** a reading arrives while the window is being aggregated. With a
naive `synchronized` list that is cleared after aggregation, the reading is either lost
(cleared) or counted twice. Swapping in a new window *before* aggregating the old one is not
enough on its own: a writer can fetch the old window just before the swap and add to it just
after it was aggregated. So a window is *closed* under its lock when it is aggregated, `add`
on a closed window fails, and the writer retries on the current window. Every reading belongs
to exactly one window. `AggregationTest` checks this with 4 writer threads and continuous
window closing (200 000 readings, none lost), and IT-Fog checks it end to end (1 000 readings
over several windows).

## 5. Deployment

- `java -jar ms_fog.jar config/fog-temp.json`. One process per data type.
- Reachable on the CF TCP port (as a peer) and the UDP API port (50201 / 50202).
- Depends on IoT devices in the overlay for data. It is useful without ML, but then has no
  subscribers.
