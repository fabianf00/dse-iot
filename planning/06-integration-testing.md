# 06 – Integration Testing

One thorough scenario per MS, with pre-conditions, post-conditions, and several steps
(input/behaviour → expected result). Plan: JUnit 5 + Awaitility, with real
sockets on localhost. Components run in-process on test ports (`51000+`) or as child processes.
Generators are deterministic (`ConstantGenerator`, fixed seeds) and windows are short, so tests
run in seconds.

Test helpers:

- `TestPeer`: a CF node that records every DATA message it receives.
- `FakeFogServer`: a UDP server that accepts subscriptions and sends scripted `AGGREGATE`s.
- `UdpTestSubscriber`: subscribes to a Fog and collects `AGGREGATE`s.
- `PortAllocator`: free ports per test so tests don't collide.

---

## IT-CF – Overlay forms, floods, respects TTL, dedupes, heals

**Pre-conditions:** 5 `PeerNode`s (A–E) on 127.0.0.1, ports 51001–51005, scan range
51001–51005, `maxOutgoingPeers=3`, `defaultTtl=3`, heartbeat 500/1500 ms, refresh 5 s.

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Start A–E | Within 5 s each node has ≤ 3 OUTGOING links, ≥ 1 link in total, and no link to itself. No duplicate link between any pair |
| 2 | A publishes 100 messages on `test.x`. B–E are subscribed | Each of B–E receives each message **exactly once** (100 distinct IDs, no duplicates). A does not receive its own messages |
| 3 | Force a line topology A–B–C–D–E (test hook disables the scanner), TTL=3, A publishes 1 message | B, C, D receive it. E does **not** (TTL exhausted) |
| 4 | Kill C (close socket without BYE) | Within `heartbeatTimeout` + a few scan ticks the remaining nodes drop C and fill their outgoing slots. A new message from A reaches B, D, E |
| 5 | Send a frame with length 10 MB to B from a raw socket | B closes that socket. All other links on B stay up |

**Post-conditions:** all nodes closed, all ports free, no `cf-*` threads alive (thread dump check).

---

## IT-IoT – Device publishes valid, ordered readings through the CF and reconnects

**Pre-conditions:** a `TestPeer` sink on 51010 (subscribed `sensor.*`). `iot-test-1` config:
`dataType=temperature`, `ConstantGenerator(21.5)`, interval 200 ms, scan range 51010–51011,
CF port 51011.

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Start `iot-test-1` | Within 3 s it connects to the sink (sink sees 1 INCOMING or OUTGOING peer `iot-test-1`) |
| 2 | Wait 2 s | Sink has ≥ 8 messages on topic `sensor.temperature`. Every payload has `value=21.5`, `unit=CELSIUS`, `deviceId=iot-test-1`, strictly increasing `sequence` and the same `bootId` |
| 3 | Start a second instance `iot-test-2` (CO2 config, port 51012) | Sink receives `sensor.co2` messages too. Temperature messages continue without a gap > 1 interval (SIMI: same JAR, different config) |
| 4 | Stop the sink, wait 2 s, restart the sink on the same port | The device keeps running (no exception). Within 3 s of the restart, readings arrive again, with the sequence continued (gap visible) |
| 5 | Start `iot-test-3` with `publishIntervalMs=0` | Startup fails fast with a validation error naming the field. The process exits non-zero |

**Post-conditions:** devices stopped, sink closed.

---

## IT-Fog – Window aggregation and UDP subscription lifecycle

**Pre-conditions:** `fog-test` (temperature, `AVG`, window 1000 ms, lease max 3000 ms, CF port
51020, UDP 51120). A `TestPeer` publisher on 51021 is connected to the fog. A
`UdpTestSubscriber` on 51130.

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Subscriber sends `SUBSCRIBE{subscriberId:t1, callbackPort:51130, leaseMs:2000}` | `SUBSCRIBE_ACK` with `dataType=temperature`, `aggregationMode=AVG`, `windowMs=1000`, lease ≤ 3000 ms |
| 2 | Within one window, publish temperature readings 10, 20, 30 and one `co2` reading 900 | Next `AGGREGATE`: `value=20.0`, `sampleCount=3` (CO2 ignored), `sequence` increases |
| 3 | Publish nothing for one window | `AGGREGATE` with `sampleCount=0`, `value=null` |
| 4 | Publish 1000 readings at high rate across ≥ 3 window boundaries | Sum of `sampleCount` across the windows == 1000 (nothing lost or double-counted at the window swap) |
| 5 | Restart the fog with `aggregationMode=MAX` (same JAR), publish 5, 7, 3 | `AGGREGATE.value=7.0`, `fogEpoch` changed, `sequence` restarted |
| 6 | Subscriber stops renewing | After lease expiry (≤ 3 s), no more `AGGREGATE`s arrive at 51130 |
| 7 | Send `{"garbage":` to 51120 | `ERROR{code:MALFORMED_REQUEST}`. Fog keeps serving other subscribers |

**Post-conditions:** fog stopped, sockets closed.

---

## IT-ML – Anomaly detection, prediction changes and RMI polling

**Pre-conditions:** two `FakeFogServer`s (temperature on 51220, CO2 on 51221). `ml-test` with
chain `temp-sudden-drop` (P: >20 in [4 s, 2 s], C: >20 in [2 s, 0 s], F: >20 within 2 s,
minSamples=2), temperature histogram binWidth 1.0, CO2 binWidth 50, `maxSamples` 20, RMI
registry on 51099. The fakes send one aggregate per 500 ms.

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Start `ml-test` | Both fakes receive a `SUBSCRIBE` with `subscriberId=ml-test`. `getStatus().fogStatus` shows both as ACTIVE within 2 s |
| 2 | Fake temperature sends 21.0, 21.2, 21.4 … for 5 s | `pollEvents(0, 100)` contains **no** anomaly. It contains a `PredictionChangeDto` for temperature with bin [21, 22) |
| 3 | Fake temperature sends 14.0 | Within 1 s, `pollEvents(cursor, 100)` returns exactly one `AnomalyDto{chainId=temp-sudden-drop, observedValue=14.0}` |
| 4 | Poll again with the new cursor, no new input | Empty event list. Same `nextCursor` |
| 5 | Fake CO2 shifts values from ~600 to ~800 for 20 samples | Exactly one new `PredictionChangeDto` for CO2 (winning bin changed once), not one per sample |
| 6 | Restart the CO2 fake (new `fogEpoch`, sequence back to 1). Also replay an old-epoch datagram | ML resubscribes after `UNKNOWN_SUBSCRIPTION`. The old-epoch datagram is ignored. New data is processed |
| 7 | Flood 5000 aggregates in 1 s | Service stays responsive (`getStatus` < 500 ms). Queue overflow is logged and counted, with no crash |

**Post-conditions:** ML stopped, RMI object unexported, registry port free.

---

## IT-CLNT / End-to-end – Full pipeline (minimum layout)

Implemented as `implementation/scripts/e2e-test.sh` (about 4–5 minutes, PASS/FAIL per check).

**Pre-conditions:** full minimum layout from `scripts/start-all.sh --demo`: 6 IoT, 2 Fog, 1 ML.
The demo configs of iot-temp-1 and iot-co2-1 inject their first anomaly about 3 minutes after
start (fixed seeds).

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Start everything, wait 30 s | Every fog has aggregated readings of all 3 of its devices (fog log). `clnt_ml --status` shows both fogs ACTIVE |
| 2 | Run `clnt_ml --once` | Exit code 0. Prints prediction lines for both data types |
| 3 | Wait for the injected drop and run `clnt_ml --once` again (same cursor file or same process) | Prints exactly the new anomaly `temp-sudden-drop` and nothing that was already printed |
| 4 | Kill ML (SIGKILL) while the client is in polling mode | Client prints `WARN unreachable`, keeps running, retries with backoff |
| 5 | Restart ML | Client prints `server restarted, cursor reset`, then continues printing new events |

**Post-conditions:** `scripts/stop-all.sh`, no leftover processes.
