# 02 – Communication Framework (CF)

## 1. Requirements → design mapping

| Requirement (assignment) | Design element |
|---|---|
| Decentralized P2P over plain TCP sockets | `java.net.Socket`/`ServerSocket`, no broker, no central registry |
| Scanning: random sockets, check availability, ranges speed up | `PeerScanner` + `ScanStrategy` (random `host:port` drawn from configured host list × port range) + HELLO handshake to verify a CF peer |
| Forward all incoming messages to all connected peers | `MessageRouter` floods DATA messages to all connections except the one it arrived on |
| Peers forgotten and refreshed in configurable intervals | `peerRefreshIntervalMs`: rolling refresh drops the oldest outgoing connection, the scanner refills it |
| Actively create connections to 3 peers, accept unlimited incoming | `maxOutgoingPeers = 3` (configurable), `PeerAcceptor` has no limit |
| Easy, transparent MS integration | Facade `CommunicationFramework` with `publish(topic, payload)` / `subscribe(topic, listener)`, built from config by a factory |
| Multi-threaded, asynchronous from MS perspective | `publish` only enqueues and returns at once. Listeners run on a dispatcher pool, never on network threads |
| TTL against endless forwarding | `ttl` field, decremented per hop, dropped at 0 |
| One more common error scenario | **Duplicate delivery through cycles**, fixed by a seen-message-ID cache (plus the others in §6) |

## 2. Component overview

```
             MS code (IoT / Fog)
                 │ publish(topic, payload)          ▲ onMessage(CfMessage)
                 ▼                                  │
 ┌──────────────── CommunicationFramework (Facade: PeerNode) ───────────────┐
 │  SubscriptionRegistry ── MessageDispatcher (executor) ──────────────────┘│
 │         ▲                                                                │
 │  MessageRouter (TTL, SeenMessageCache, flooding)                         │
 │         ▲  │                                                             │
 │  ConnectionManager  (peers: Map<NodeId, PeerConnection>, OUT ≤ 3, IN ∞)  │
 │     ▲          ▲              ▲                 ▲                        │
 │ PeerAcceptor PeerScanner  HeartbeatMonitor  PeerRefresher                │
 │ (ServerSocket) (ScanStrategy)                                            │
 │                                                                          │
 │  PeerConnection: Socket + reader thread + writer thread (bounded queue)  │
 │  FrameCodec: [4-byte length][UTF-8 JSON]                                 │
 └──────────────────────────────────────────────────────────────────────────┘
```

### Threads per node

| Thread / pool | Count | Job |
|---|---|---|
| `cf-acceptor` | 1 | `ServerSocket.accept()`, runs the handshake, registers INCOMING peers |
| `cf-scheduler` (ScheduledExecutor) | 1–2 | scan tick, heartbeat tick, refresh tick, seen-cache cleanup |
| `cf-reader-<peer>` | 1 per connection | blocking `readFrame()`, hands messages to `MessageRouter` |
| `cf-writer-<peer>` | 1 per connection | drains the connection's bounded outbound queue |
| `cf-handshake` | pool of 4 | runs handshakes of incoming connections, so a silent client cannot block `accept()` |
| `cf-dispatch` | 1 | calls the MS `MessageListener`s, one message at a time in arrival order |

## 3. Configuration (`cf` block in every IoT and Fog config)

Only `nodeId`, `listenPort` and `scan.hosts`/`scan.portRange` are required. A missing (or 0)
value means the default shown below. Without a `scan` block the node is *passive*: it accepts
peers but never looks for any (logged as a warning).

```json
"cf": {
  "nodeId": "iot-temp-1",
  "listenHost": "0.0.0.0",
  "listenPort": 50101,
  "advertisedHost": "127.0.0.1",
  "scan": {
    "hosts": ["127.0.0.1"],
    "portRange": { "from": 50100, "to": 50129 },
    "intervalMs": 500,
    "connectTimeoutMs": 300,
    "handshakeTimeoutMs": 1000,
    "failedCandidateBackoffMs": 10000,
    "maxAttemptsPerRound": 5
  },
  "maxOutgoingPeers": 3,
  "peerRefreshIntervalMs": 60000,
  "heartbeatIntervalMs": 5000,
  "heartbeatTimeoutMs": 15000,
  "defaultTtl": 6,
  "seenCache": { "expiryMs": 60000, "maxEntries": 20000 },
  "outboundQueueCapacity": 1000,
  "maxFrameBytes": 65536
}
```

## 4. Wire format (general message format)

**Framing:** each frame is a 4-byte unsigned big-endian payload length followed by that many
bytes of UTF-8 JSON. Frames with length `0` or `> maxFrameBytes` are protocol violations and the
connection is closed.

**Envelope:**

```json
{
  "v": 1,
  "id": "3f7a1c52-8a8e-4f7b-9d7e-5b2f8f0c9a11",
  "type": "DATA",
  "origin": "iot-temp-1",
  "sender": "iot-co2-2",
  "ttl": 4,
  "createdAt": 1637412185123,
  "topic": "sensor.temperature",
  "payload": { "...": "type/topic specific JSON object" }
}
```

| Field | Type | Meaning |
|---|---|---|
| `v` | int | Protocol version (currently 1). Unknown major version → `BYE{reason: UNSUPPORTED_VERSION}` |
| `id` | UUID string | Globally unique message ID, set once by the origin. Used for de-duplication |
| `type` | enum | `HELLO`, `HELLO_ACK`, `PING`, `PONG`, `BYE`, `DATA` |
| `origin` | string | nodeId of the creator |
| `sender` | string | nodeId of the last hop (rewritten on every forward) |
| `ttl` | int ≥ 0 | Remaining hops. Only meaningful for `DATA` |
| `createdAt` | epoch ms | Origin's timestamp, for information only. Never used for windowing (clock skew) |
| `topic` | string | Only for `DATA`. Dot-separated, e.g. `sensor.temperature`. Subscriptions may use the `sensor.*` wildcard |
| `payload` | JSON object | Control-specific or application data |

### Message types

| Type | Scope | Payload | Purpose |
|---|---|---|---|
| `HELLO` | hop-local | `{ "nodeId", "listenPort", "advertisedHost", "role": "IOT|FOG|…", "magic": "DSE-IOT-CF" }` | First frame from the connecting side. Identifies a CF peer |
| `HELLO_ACK` | hop-local | `{ "nodeId", "accepted": true|false, "reason": null|"SELF_CONNECTION"|"ALREADY_CONNECTED"|"UNSUPPORTED_VERSION" }` | Answer from the accepting side. On `false`, both close |
| `PING` / `PONG` | hop-local | `{}` | Liveness check when a link is idle |
| `BYE` | hop-local | `{ "reason": "REFRESH"|"SHUTDOWN"|"PROTOCOL_ERROR"|… }` | Graceful close. The peer removes the link right away |
| `DATA` | flooded | application payload (see MS docs) | Application data. Forwarded with TTL−1 |

Control messages are **never forwarded** and their TTL is ignored.

## 5. Protocol sequences

### 5.1 Discovery and handshake (scanner side)

```
Scanner (A)                                         Candidate host:port
  │  outgoing < 3 ?  pick random (h,p) ∉ {self, connected, backoff}
  │── TCP connect (timeout 300 ms) ───────────────────►│
  │      fails → put (h,p) in backoff list, next tick   │
  │── HELLO {nodeId:A, magic, v:1} ───────────────────►│
  │◄─ HELLO_ACK {nodeId:B, accepted:true} ─────────────│   (within 1000 ms)
  │  register B as OUTGOING, start reader/writer        │
  │  (no/invalid answer → close, backoff (h,p))         │
```

The accepting side rejects with `SELF_CONNECTION` if `HELLO.nodeId == own nodeId`, and with
`ALREADY_CONNECTED` if a connection with that nodeId already exists in either direction. This
keeps the 3 outgoing links on 3 *distinct* neighbours.

**Simultaneous connect:** if A and B connect to each other at the same moment, both handshakes
succeed before either side registers a link. Both nodes then keep the link *opened by the node
with the smaller nodeId* and close the other with `BYE{DUPLICATE_CONNECTION}`. Both sides apply
the same rule, so they agree without extra messages. If both links come from the same side (a
peer reconnected before its old link timed out), the newer link wins.

### 5.2 Publishing and forwarding

```
publish(topic, payload)  [MS thread, returns at once]
  → build DATA {id: new UUID, origin=sender=self, ttl=defaultTtl}
  → seenCache.add(id)
  → enqueue in every PeerConnection outbound queue

onFrame(msg, fromConnection)  [reader thread]
  1. validate envelope        → invalid: count error, ≥3 errors → BYE{PROTOCOL_ERROR}, close
  2. if seenCache.contains(id) → drop (duplicate)
  3. seenCache.add(id)
  4. dispatcher.deliver(msg)   → every local subscriber whose topic pattern matches (async)
  5. if msg.ttl - 1 > 0        → copy {ttl-1, sender=self}, enqueue to all connections ≠ fromConnection
     else                      → do not forward (TTL expired)
```

### 5.3 Liveness and refresh

- **Heartbeat:** if nothing was sent on a link for `heartbeatIntervalMs`, send `PING`.
  The receiver answers with `PONG`. If nothing at all was received within `heartbeatTimeoutMs`,
  the link is closed (catches half-open TCP connections).
- **Rolling refresh:** every `peerRefreshIntervalMs`, send `BYE{REFRESH}` on the *oldest*
  outgoing link and close it. The next scan tick fills the slot with a random candidate, which
  may be the same peer again. Incoming links are never refreshed by the receiver; their owner
  refreshes them.
- **Shutdown:** `close()` sends `BYE{SHUTDOWN}` on all links, stops the scanner and acceptor,
  and drains the executors.

## 6. Error scenarios (detected proactively)

| # | Scenario | Detection | Mitigation |
|---|---|---|---|
| E1 (required) | Endless forwarding loops | – | `ttl` decremented per hop, not forwarded at 0 |
| **E2 (our "additional common error")** | **Duplicate delivery**: in a mesh with cycles, a message reaches a node over several paths before its TTL runs out, so the MS sees the reading 2–3 times and Fog averages are skewed | seen-ID cache hit | `SeenMessageCache` (bounded LRU with time-based expiry, keyed by `id`). Duplicates are neither delivered nor forwarded. This also cuts network load sharply |
| E3 | Peer crashes, battery dies, or cable unplugged (half-open TCP) | IOException on read/write, heartbeat timeout | Close the link and remove the peer. The scanner refills the outgoing slots |
| E4 | Scanner reaches its *own* port, or a peer it is already connected to | `HELLO.nodeId` | `HELLO_ACK{accepted:false, SELF_CONNECTION / ALREADY_CONNECTED}` |
| E5 | Scanned port belongs to a foreign service and returns garbage | missing `magic`, frame too big, handshake timeout | Close, put candidate in backoff (`failedCandidateBackoffMs`) |
| E6 | Slow or blocked peer would stall forwarding to everyone | outbound queue full | Bounded queue per connection: drop the oldest DATA frame and log a warning. Sensor streams tolerate loss, and other peers are not affected |
| E7 | Malformed JSON / unknown type / negative TTL | validation in `MessageRouter` | Drop the message and count it. At 3 errors, `BYE{PROTOCOL_ERROR}` and close |
| E8 | Exception in an MS listener | caught in dispatcher | Log it, keep delivering to the other listeners. Network threads are never affected |
| E9 | Two nodes connect to each other at the same moment | second registration for the same nodeId | Deterministic tie-break: keep the link opened by the smaller nodeId (§5.1) |
| E10 | BYE cannot be written (peer stopped reading) | link still open `heartbeatTimeoutMs` after BYE was queued | Heartbeat monitor force-closes it |

### Edge case: **overlay partition ("islands")**

With 3 random outgoing links, a group of nodes can end up linked only to each other, e.g. the
3 temperature devices plus `fog-temp` form a closed cluster. Then `fog-co2` never receives CO2
data, although every node is "healthy" and has 3 peers. A naive design that only scans when it
has fewer than 3 peers would **never** recover.

Mitigation in our protocol design:

1. **Rolling refresh** with fresh *random* candidates breaks clusters: over time every node tries
   links outside its island. With N nodes and 3 outgoing links, the chance that a refresh
   reconnects to the same island drops quickly.
2. **Incoming links are unlimited**, so a node from another island can always attach to us,
   even if all our outgoing slots are full.
3. **TTL sized for the overlay diameter** (default 6 for ~10 nodes, configurable) lets messages
   cross the merged overlay.
4. Fog nodes log a warning when their window stays empty for k windows. This makes the
   partition visible in operations, and the integration test checks for it.

This is also why `defaultTtl` must not be set too low. Trade-off:
higher TTL = more redundant traffic, but dedupe (E2) limits the cost.

## 7. MS integration

What MS code sees: one interface, one factory, and plain Java objects.

```java
CfConfig cfConfig = config.cf();                                  // part of the MS config JSON
try (CommunicationFramework cf = CommunicationFrameworkFactory.create(cfConfig)) {
    cf.subscribe("sensor.temperature", msg -> {                   // Fog: async callback
        SensorReading r = msg.payloadAs(SensorReading.class);
        collector.add(r);
    });
    cf.start();                                                   // acceptor, scanner, timers
    cf.publish("sensor.temperature", reading);                    // IoT: non-blocking
}
```

Design decisions that make integration easy:

- **Facade + factory:** an MS never touches sockets, threads, framing or JSON.
- **Typed payloads:** `publish(String topic, Object payload)` serializes any POJO or record.
  `CfMessage.payloadAs(Class<T>)` deserializes it.
- **Topic-based subscriptions** with wildcards, so a Fog node only receives its data type,
  although flooding moves everything everywhere.
- **Config-driven:** the CF block lives in each MS's JSON config. SIMI instances only differ in
  `nodeId`/`listenPort`.
- **Async contract:** `publish` never blocks on the network. Listener callbacks run on the
  dispatcher pool, so MS code may be slow without affecting forwarding.
- **Observability:** `connectedPeers()` returns `PeerInfo{nodeId, address, direction, connectedSince}`. Used for status logs and tests.

## 8. UML class diagram (draft → `implementation/uml/cf.puml`)

```plantuml
@startuml cf
package at.ac.univie.dse.iot.cf {
  interface CommunicationFramework <<AutoCloseable>> {
    +start() : void
    +publish(topic : String, payload : Object) : void
    +subscribe(topicPattern : String, listener : MessageListener) : Subscription
    +nodeId() : NodeId
    +connectedPeers() : Set<PeerInfo>
    +close() : void
  }
  interface MessageListener { +onMessage(message : CfMessage) : void }
  interface Subscription { +cancel() : void }
  class CommunicationFrameworkFactory { +{static} create(config : CfConfig) : CommunicationFramework }

  class PeerNode implements CommunicationFramework {
    -config : CfConfig
    -connectionManager : ConnectionManager
    -router : MessageRouter
    -subscriptions : SubscriptionRegistry
    -scheduler : ScheduledExecutorService
  }

  class ConnectionManager {
    -peers : ConcurrentMap<NodeId, PeerConnection>
    +register(conn : PeerConnection) : boolean
    +remove(id : NodeId, reason : String) : void
    +outgoingCount() : int
    +oldestOutgoing() : Optional<PeerConnection>
    +broadcast(msg : CfMessage, except : NodeId) : void
    +admission(remote : NodeId) : Optional<RejectReason>
    +{static} keepCandidate(self : NodeId, remote : NodeId, existing : Direction, candidate : Direction) : boolean
  }
  class PeerConnection {
    -remoteId : NodeId
    -direction : Direction
    -socket : FramedSocket
    -outbound : BlockingQueue<CfMessage>
    -lastReceivedAt : Instant
    -connectedSince : Instant
    +start() : void
    +send(msg : CfMessage) : boolean
    +closeGracefully(reason : ByeReason) : void
    +close(reason : String) : void
  }
  enum Direction { INCOMING
    OUTGOING }
  class PeerAcceptor { +start() : void
    +stop() : void }
  class PeerScanner { +scanRound() : void
    +backoff(address : InetSocketAddress) : void }
  interface ScanStrategy { +nextCandidate(exclude : Set<InetSocketAddress>) : Optional<InetSocketAddress> }
  class RandomRangeScanStrategy implements ScanStrategy
  class HandshakeHandler {
    +initiate(socket : FramedSocket, target : InetSocketAddress) : Result
    +accept(socket : FramedSocket) : Result
  }
  class HeartbeatMonitor { +check() : void }
  class PeerRefresher { +refresh() : void }

  class MessageRouter {
    +publish(topic : String, payload : JsonNode) : CfMessage
    +onData(msg : CfMessage, from : NodeId) : void
  }
  class SeenMessageCache {
    +markIfNew(id : UUID) : boolean
    +evictExpired() : void
  }
  class SubscriptionRegistry {
    +add(pattern : String, l : MessageListener) : Subscription
    +matching(topic : String) : List<MessageListener>
  }
  class MessageDispatcher { +deliver(msg : CfMessage) : void }
  class FrameCodec {
    +encode(msg : CfMessage) : byte[]
    +write(out : DataOutputStream, msg : CfMessage) : void
    +read(in : DataInputStream) : CfMessage
  }
  class FramedSocket {
    +read() : CfMessage
    +write(msg : CfMessage) : void
    +flush() : void
  }
  class CfMessage <<record>> {
    v : int
    id : UUID
    type : MessageType
    origin : NodeId
    sender : NodeId
    ttl : int
    createdAt : long
    topic : String
    payload : JsonNode
    +forwardedBy(self : NodeId) : CfMessage
    +payloadAs(type : Class<T>) : T
  }
  enum MessageType { HELLO
    HELLO_ACK
    PING
    PONG
    BYE
    DATA }
  class NodeId <<record>> { value : String }
  class PeerInfo <<record>> { nodeId : NodeId
    address : InetSocketAddress
    direction : Direction
    connectedSince : Instant }
  class CfConfig <<record>>
  class CfException

  PeerNode *-- ConnectionManager
  PeerNode *-- MessageRouter
  PeerNode *-- SubscriptionRegistry
  PeerNode *-- PeerAcceptor
  PeerNode *-- PeerScanner
  PeerNode *-- HeartbeatMonitor
  PeerNode *-- PeerRefresher
  PeerScanner --> ScanStrategy
  PeerScanner --> HandshakeHandler
  PeerAcceptor --> HandshakeHandler
  PeerScanner --> ConnectionManager
  PeerAcceptor --> ConnectionManager
  ConnectionManager o-- "0..*" PeerConnection
  PeerConnection --> FramedSocket
  FramedSocket --> FrameCodec
  MessageRouter --> SeenMessageCache
  MessageRouter --> ConnectionManager
  MessageRouter --> MessageDispatcher
  MessageDispatcher --> SubscriptionRegistry
  SubscriptionRegistry o-- MessageListener
}
@enduml
```

## 9. Tests

Implemented in `cf/src/test`: `FrameCodecTest`, `CfMessageTest`, `RoutingTest` (router, seen cache,
topics, subscriptions), `DiscoveryTest` (scan strategy, scanner backoff, duplicate-link rule),
`CfConfigTest` and `CfOverlayIntegrationTest` (IT-CF). Originally planned:


- `FrameCodecTest`: round trip, oversized frame rejected, truncated stream → `EOFException`.
- `SeenMessageCacheTest`: dedupe, expiry, max size.
- `MessageRouterTest` (with mocked `ConnectionManager`): TTL decrement, TTL 1 not forwarded,
  not echoed back to sender, duplicates dropped.
- `TopicMatcherTest`: exact match, `sensor.*`.
- `RandomRangeScanStrategyTest`: never returns excluded or self addresses.
