# 11 – Testing After the Change Request

## 1. New test structure

The assignment asks for integration tests "in a separate project", with logic shared and
reused wherever possible, covering the new REST API and the DESIGN APIs.

```
implementation/
  test_support/        shared test library (Maven module, normal "main" code, used in test scope)
  intgrtn_tests/       all integration tests (Maven module, maven-failsafe, *IT.java)
  <module>/src/test    unit tests stay with their module
```

- `test_support` depends on `common`, `cf` and `ml_api` only (no cycles). The service modules
  use it in **test** scope for their unit tests (e.g. `FakeFogServer` in `ms_ml`,
  `FakeMlService` in `clnt_ml`).
- `intgrtn_tests` depends on every module plus `test_support`. The ITs keep the **package
  names** of the code they test (e.g. `at.ac.univie.dse.iot.cf`), so package-private test
  hooks such as `PeerNode.connectTo`/`abort` or `MlClientApplication.run(args, out, err)` keep
  working from the other module (classpath, no JPMS).
- `./mvnw verify` runs unit tests (surefire) and integration tests (failsafe).
  `./mvnw test` runs only unit tests. `-DskipITs` skips the ITs.

## 2. `test_support` contents (moved duplicates, then new)

| Helper | Was | Purpose |
|---|---|---|
| `Ports` | 3 copies of `freePorts`/`freeUdpPort` | free TCP port ranges, UDP ports on 127.0.0.1 |
| `Waiting` | ad-hoc `Thread.sleep` loops | Awaitility defaults (timeouts, poll interval) |
| `CfTestPeer` | sink/publisher code in IoT and Fog ITs | passive or scanning CF node that records received readings and publishes readings |
| `FakeFogServer` | `ms_ml` test | scriptable fog over UDP (subscribe, renew, aggregates, restart, replay) |
| `UdpFogTestClient` | `ms_fog` IT | Fog API client that keeps answers and aggregates apart |
| `FakeMlService` | `clnt_ml` test | in-memory `MlRemoteService` with the server's paging/epoch rules |
| `RmiTestServer` | `clnt_ml` IT | export a service with its own registry, stop and restart |
| `RestTestClient` (new) | – | `java.net.http.HttpClient` + Jackson: GET/POST/PUT/DELETE, JSON bodies, status, headers |
| `TestConfigs` (new) | inline JSON in tests | builders for IoT/Fog/ML/WEB configs with free ports and short windows |
| `RunningSystem` (new) | – | starts a chosen set of services in-process (IoT, Fog, ML, WEB) and stops them in reverse order (AutoCloseable) |

## 3. Integration test scenarios

DESIGN scenarios (06), moved and adapted:

| IT | From | Change |
|---|---|---|
| IT-CF `CfOverlayIT` | `cf` | moved; uses `Ports`, `Waiting` |
| IT-IoT `IotDeviceIT` | `ms_iot` | moved; sink is `CfTestPeer` |
| IT-Fog `FogNodeIT` | `ms_fog` | moved; `UdpFogTestClient`, `CfTestPeer` |
| IT-ML `MlServiceIT` | `ms_ml` | moved; plus: every anomaly/prediction event seen over RMI is also stored (REST `total` grows by the same number) |
| IT-CLNT `MlClientIT` | `clnt_ml` | moved; `RmiTestServer`, `FakeMlService` |

New scenarios:

**IT-REST – ML REST API** (`MlRestApiIT`)

Pre-conditions: ML in-process with two `FakeFogServer`s, H2 in a temp directory, REST on a
free port, short chains (as in IT-ML).

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Fake fogs send normal values, then a temperature drop | `GET /anomalies` has 1 item (chain `temp-sudden-drop`, observed value). `GET /predictions` ≥ 2 items. `total` matches the RMI events of the same kinds |
| 2 | Filters: `dataType=co2`, `chainId`, `from/to` around the drop, `commented=false`, `limit=1&offset=1`, `sort=occurredAt,asc` | Only matching items, correct `total`, order and paging |
| 3 | `POST /anomalies/{id}/comments {"text":"window open"}` | 201 with `Location`. `GET /anomalies/{id}` shows the comment. `commented=true` now finds it |
| 4 | `PUT /comments/{cid}`, then `DELETE /comments/{cid}` | 200 with new text and `updatedAt`; then 204, and the comment is gone |
| 5 | `DELETE /anomalies/{id}`, then `GET` and `POST …/comments` on it | 204; then 404, 404 |
| 6 | `DELETE /predictions?dataType=co2` | 200 `{"deleted": n}`; `GET /predictions?dataType=co2` is empty, temperature is untouched |
| 7 | `DELETE /anomalies` (no filter), `limit=0`, `from=yesterday`, `chainId` on predictions, empty comment, non-JSON body | 400 problem details naming the problem; 415 for a wrong content type |
| 8 | `GET /predictions/current`, `GET /status`, `GET /meta` | Live predictions equal RMI `getCurrentPredictions()`. Status equals RMI status plus storage counters. `meta` lists the configured chains |
| 9 | CORS preflight `OPTIONS /anomalies` with `Origin: http://127.0.0.1:50400`, and with a foreign origin | Allowed for the configured origin, not for the foreign one |

**IT-PERSIST – history survives restarts** (`MlPersistenceIT`)

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Produce an anomaly, comment it, stop ML | – |
| 2 | Start a new ML instance on the same database | `GET /anomalies` still has the anomaly with its comment. RMI `pollEvents(0)` is empty (new epoch, in-memory log) |
| 3 | Produce another anomaly | Stored with the new `serverEpoch`; no duplicates of old events |

**IT-WEB – WEB ML** (`WebMlIT`)

| # | Input / behaviour | Expected result |
|---|---|---|
| 1 | Start web_ml and ML (REST) | `GET /` → 200 `text/html` containing the app script; `GET /app.js`, `/style.css`, `/vendor/chart.umd.min.js` → 200 with correct content types |
| 2 | `GET /config.json` | Lists the ML REST URL from the web_ml config |
| 3 | `GET /../pom.xml`, `/%2e%2e/config.json`, `POST /` | 404, 404, 405 |
| 4 | Store a comment with `<script>alert(1)</script>` through REST | REST returns it unchanged (the UI renders it as text; see 10 §5) |
| 5 | Static check of the UI sources | No `localStorage`/`sessionStorage`/`indexedDB`/`document.cookie`; `innerHTML` is never used with API data (grep-based guard for the "no storage, minimal logic" rules) |

**IT-E2E – full pipeline in-process** (`FullSystemIT`)

6 IoT devices with short intervals, one with a seeded anomaly; 2 fogs with 1 s windows; ML
with short chains, REST and RMI; web_ml. Expected: both fogs see 3 devices each, ML is ACTIVE
at both fogs (RMI status and REST status agree). The anomaly arrives over RMI (CLNT ML
`--once` prints it) **and** over REST (stored, can be commented). It runs in about 30–60 s.
Seeds and windows are chosen like the demo configs. The script `scripts/e2e-test.sh` stays as
the system test with the real JARs and gets REST and web checks (curl).

## 4. Unit tests added by the change request

| Module | New unit tests |
|---|---|
| `ms_ml` | `JpaResultRepositoryTest`, `ResultRecorderTest`, `ResultServiceTest`, `FilterParserTest`, `RestApiTest`, extended `EventLogTest` (09 §5) |
| `web_ml` | `StaticFilesTest`, `WebMlConfigTest`, `WebMlServiceTest` (10 §6) |
| `test_support` | small tests for `Ports` and `RestTestClient` |
| others | unchanged; every MS and the CF already have unit tests of their core algorithms (routing/TTL/dedupe, generators, window swap, chains, histograms, polling) |

Test quality rules kept: one behaviour per test, real sockets only in ITs, deterministic
data (constant generators, seeds, fake fogs), Awaitility instead of fixed sleeps where
possible, every IT cleans up its ports, threads and temp directories.
