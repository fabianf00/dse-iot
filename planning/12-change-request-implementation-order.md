# 12 – Implementation Order for the Change Request (Steps 9–16)

Continues 07 (steps 0–8 are done). Every step ends with `./mvnw verify` green and, where it
touches running services, a short run with the scripts.

## Step 9 – Build changes

- Parent POM: modules `test_support`, `web_ml`, `intgrtn_tests`. Dependency management for
  Hibernate ORM 7.4, H2 2.5, Javalin 7.2 (Jackson aligned with our BOM), maven-failsafe 3.5.
- Check early: shaded `ms_ml.jar` with Hibernate and Jetty starts (service files, logging).
  If shading causes trouble, switch `ms_ml` to `maven-assembly`/`appassembler` with a lib folder.

## Step 10 – `test_support` and `intgrtn_tests` (safety net first)

1. Create `test_support` with `Ports`, `Waiting`, `CfTestPeer`, `FakeFogServer`,
   `UdpFogTestClient`, `FakeMlService`, `RmiTestServer`.
2. Move the five `*IntegrationTest` classes into `intgrtn_tests` as `*IT` (same packages),
   switch them to the shared helpers, and delete the duplicates.
3. Unit tests in `ms_ml` and `clnt_ml` use `test_support` in test scope.

Done when: same tests, all green, no duplicated helpers left.

## Step 11 – MS ML storage

1. `StorageConfig`, `Database` (programmatic `EntityManagerFactory`, H2), `Transactions`.
2. Entities: `MlResultEntity`, `AnomalyEntity`, `PredictionEntity`, `CommentEntity`,
   `PhaseObservationEmbeddable`.
3. `ResultRepository` + `JpaResultRepository` (Criteria API only), `ResultFilter`,
   `PageRequest`, `Page`.
4. `EventLog` listeners, `ResultRecorder` (own thread, bounded queue, idempotent).
5. Wire into `MlService` (start/stop order). Extend `ServiceStatusDto` usage with storage
   counters in the REST status only (the RMI DTO stays unchanged).
6. Unit tests (09 §5). Add a guard test that fails if source files contain SQL/JPQL keywords in
   string literals (`"SELECT `, `"DELETE FROM`, `createQuery(`, `createNativeQuery(`).

## Step 12 – MS ML REST API

1. `RestConfig`, `RestServer` (Javalin, Jackson mapper from `JsonCodec`, CORS, problem
   details, 404/405 handling).
2. `ResultService`, controllers (`ResultsController`, `CommentsController`, `LiveController`),
   `FilterParser`, `ResourceMapper`.
3. Bulk-delete race mitigation (`recordedAt` bound, 09 §3.4).
4. Unit tests, then IT-REST and IT-PERSIST.
5. Update `config/ml-1.json` (`storage`, `rest`) and the demo copy if needed.

## Step 13 – WEB ML

1. Module `web_ml`: `WebMlConfig`, `StaticFiles`, `WebMlService`, `WebMlApplication`.
2. Static UI: `index.html`, `style.css`, `app.js`; vendored Chart.js 4.5.1 with license.
3. Live view, history view, details/comments, delete; URL-driven state; error banner.
4. Unit tests, IT-WEB, manual check list (10 §6).
5. `config/web-ml.json`.

## Step 14 – Deployment

- `start-all.sh`: start ML (with REST and DB), fogs, IoT, then web_ml. Start processes with
  `implementation/` as working directory, create `run/data/`.
- `status.sh`: also shows the REST and WEB URLs. New `scripts/reset-data.sh` deletes
  `run/data/` (asks first).
- `e2e-test.sh`: additional checks with curl: the anomaly is stored (`GET /anomalies`),
  comment round trip, web_ml serves `/` and `/config.json`.
- README: new ports, WEB ML URL, REST examples (curl), manual UI checks.

## Step 15 – In-process end-to-end IT and test review

- `RunningSystem`, `TestConfigs`, `FullSystemIT` (11 §3).
- Review all tests against the quality rules (11 §4). Remove fixed sleeps where Awaitility
  can be used.

## Step 16 – UML and docs

- `uml/ms_ml.puml`: packages `storage`, `results`, `rest`. New `uml/web_ml.puml`.
  Optionally `uml/intgrtn_tests.puml` for the test support structure.
- `check-uml.sh`: add `web_ml` (and `test_support` if diagrammed). Render the PNGs.
- Final full system run: all instances, browser on `http://127.0.0.1:50400/`, an anomaly
  visible in the chart, commented and deleted.

## Risks

| Risk | Mitigation |
|---|---|
| Hibernate/Jetty in a shaded fat JAR (service loader files, signed jars, logging bridges) | Check in step 9; fall back to a lib folder |
| Jackson version conflicts (Javalin vs our BOM) | Manage Jackson only through our BOM; Javalin uses the provided mapper |
| H2 file locked by a second ML process using the same file | One `jdbcUrl` per instance (contains the `serviceId`); clear error message at start |
| "No explicit SQL" broken by accident (JPQL, native queries) | Criteria API only, guard test (step 11) |
| DB slower than the event rate under load | Recorder queue decouples it; failures counted and visible in the status |
| XSS through comments | `textContent` only; IT-WEB stores a script comment |
| CORS misconfiguration (UI opened via `localhost` vs `127.0.0.1`) | Both origins in the defaults; the IT-REST preflight checks it |
| Integration tests getting slow | Short windows and seeds; full system only once (`FullSystemIT`) |

## Decisions to confirm before starting

1. **REST framework:** Javalin (recommended) instead of Spring Boot. Spring Boot would also
   bring Spring Data JPA, but it would replace the existing lifecycle and DI style.
2. **Delete semantics:** hard delete (recommended) or soft delete with a "deleted" filter.
3. **Database:** embedded H2 file (recommended) or a PostgreSQL server (then a Docker
   Compose file for the database would be added).

## Progress notes

**Step 9 – done.** Decisions taken with the recommended options: Javalin, hard delete, embedded H2.

- Parent POM: modules `web_ml`, `test_support`, `intgrtn_tests`. Managed versions: Hibernate ORM
  7.4.12.Final, H2 2.5.252, Javalin 7.2.3, failsafe 3.5.6 (failsafe in `pluginManagement`, used
  by `intgrtn_tests`; `-DskipITs` skips it).
- **Fat JARs changed:** the shade plugin now writes `target/<module>.jar` as a separate file
  (`outputFile`) instead of replacing the module's artifact. Modules that depend on a service
  (`intgrtn_tests`) get the plain JAR and no duplicate classes. Paths for the scripts are
  unchanged (`ms_iot/target/ms_iot.jar`, …).
- `ms_ml` depends on Hibernate, H2 and Javalin. One Jackson (2.22.3) and one SLF4J (2.0.20) on
  the classpath; Javalin adds Jetty 12 and the Kotlin standard library. `ms_ml.jar` is 35 MB.
- Shaded-JAR check (throwaway program in the scratchpad, against `ms_ml.jar`): Hibernate with
  `PersistenceConfiguration` (no `persistence.xml`), H2 in memory, `hibernate.hbm2ddl.auto=update`,
  Criteria count with a `like` filter → correct result. Javalin with `JavalinJackson`, a route
  in `config.routes`, and the CORS plugin (`bundledPlugins.enableCors`) → 200, JSON, correct
  `Access-Control-Allow-Origin`.
- Javalin 7 API notes: routes, exception and error handlers are registered in
  `Javalin.create(config -> config.routes.…)`; CORS via `config.bundledPlugins.enableCors(...)`.
- For step 11: Hibernate warns "Using built-in connection pool (not intended for production
  use)". Add a real pool (`hibernate-hikaricp` + HikariCP, or Agroal) when building `Database`.
- `web_ml` (stub main, shade), `test_support` (empty), `intgrtn_tests` (no main code, empty JAR
  skipped) build. All 257 existing tests pass.

**Step 10 – done.**

- `test_support` (main code, used in test scope by `ms_ml`, `clnt_ml` and `intgrtn_tests`): `Ports`
  (free TCP ranges, TCP and UDP ports), `Waiting` (Awaitility defaults: 15 s, 50 ms poll; `pause`
  for proving that something does not happen), `CfTestPeer` (passive or scanning CF node that
  records readings and publishes), `FakeFogServer` and `FakeMlService` (moved with `git mv`),
  `UdpFogTestClient` (was nested in the fog IT), `RmiTestServer` (export with own registry,
  stop and restart on the same port).
- The five integration tests moved to `intgrtn_tests` as `CfOverlayIT`, `IotDeviceIT`,
  `FogNodeIT`, `MlServiceIT`, `MlClientIT`, in their original packages. They use the shared
  helpers; the five copies of the port helpers, the nested UDP client, the RMI start/stop code
  and the sink/publisher setup are gone.
- Unit tests stay in the modules (233); integration tests run in failsafe (24). Total unchanged (257).
  The 24 ITs passed three times in a row in one JVM.
- `intgrtn_tests` has no main code: empty JAR skipped, and the compiler's incremental check is
  off there (it warned about the missing `target/classes`).

**Step 11 – done.**

- Package `msml.storage`: `StorageConfig` (optional `storage` block; default
  `jdbc:h2:file:./run/data/<serviceId>`, user, password, recorder queue, pool size),
  `Database` (programmatic `PersistenceConfiguration`, Hibernate schema update,
  `CamelCaseToUnderscoresNamingStrategy`, UTC, HikariCP pool via `hibernate-hikaricp`),
  entities `MlResultEntity` (single table, discriminator `kind`, unique
  `(ml_instance, server_epoch, event_sequence)`), `AnomalyEntity`, `PredictionEntity`,
  `CommentEntity`, `PhaseObservationEmbeddable`, `ResultRepository` + `JpaResultRepository`,
  `ResultFilter` (incl. `recordedBefore` for the bulk-delete race), `PageRequest`, `Page`,
  `ResultRecorder`.
- Deviations from 09:
  - No separate `Transactions` class: `Database.inTransaction(Function)` and
    `Database.runInTransaction(Consumer)` (two overloads with the same name were ambiguous for
    block lambdas).
  - The repository returns immutable records (`StoredResult.StoredAnomaly/StoredPrediction`,
    `StoredComment`, `StoredPhase`) built inside the transaction. Entities never leave the
    storage package, so no lazy-loading errors outside a transaction. Lists load one page of
    results, then the comments of that page in one more Criteria query.
  - Bulk delete selects the matching ids (Criteria), then deletes comments and results by id in
    chunks of 500 with `CriteriaDelete` (Criteria deletes cannot use `treat` for `chainId`).
  - Column names avoid SQL keywords: `comment_text`, embedded phase columns `past_condition`,
    `past_start`, `past_end`, `past_samples` (and `current_…`).
- `EventLog.addListener`: listeners are called after the append, outside the lock; a failing
  listener is logged and does not affect others. `MlService` opens the database, registers and
  starts the recorder before the workers, and stops recorder (drains its queue) and database
  last.
- Done early from step 14: `start_instance` starts every process with `implementation/` as working
  directory (relative DB paths no longer depend on where the script is called from) and creates
  `run/data/`.
- Tests: `JpaResultRepositoryTest` (13: all fields, idempotency, every filter, paging/sort,
  comments, cascade, bulk delete, file DB reopen, clear error for an unusable DB),
  `ResultRecorderTest` (5), `NoExplicitSqlTest` (2: scans `src/main/java` for SQL/JPQL strings,
  native and named queries, JDBC statements), 3 new `EventLogTest`, 3 new `MlConfigTest`.
  `MlServiceIT` uses an in-memory DB. 281 tests in total (257 unit, 24 IT).
- Real run (demo layout from the JARs, started from the repository root): `run/data/ml-1.mv.db`
  created under `implementation/`, 2 prediction changes recorded and read back after the
  processes ended.

**Step 12 – done.**

- `msml.results.ResultService` holds the use cases and validation (`InvalidRequestException`).
  Comment text is trimmed, 1–2000 characters. The author is optional (blank means none) and at most
  100 characters. A bulk delete without a filter is refused, and every bulk delete gets
  `recordedBefore` = now (race mitigation, plan 09 §3.4).
- `msml.rest`:
  - `RestServer`: Javalin 7 with the Jackson mapper from `JsonCodec`, banner off,
    `prefer405over404`. CORS comes from `rest.allowedOrigins` and exposes `Location`; an empty
    list disables CORS.
  - The `/predictions/current`, `/status` and `/meta` routes are registered before the `{id}`
    routes.
  - `ResultsController` (one instance per kind), `CommentsController`, `LiveController`.
  - `FilterParser`: unknown parameters give 400, `chainId` is rejected on predictions, a bulk
    delete takes no paging parameters, and a repeated single-value parameter gives 400.
  - `ResourceMapper`, plus `Resources` (all JSON records, including `ProblemDetails`), and
    `ApiException` and `Ids` (positive ids only).
- Errors are RFC 9457 problem details (`application/problem+json`, `type` `about:blank`):
  - `ApiException` maps to its own status; `InvalidRequestException` gives 400.
  - `StorageException` gives 503; anything else gives 500 (logged).
  - Javalin's own 404 and 405 get a problem body too.
  - A closed `Database` now throws `StorageException` (it used to be Hibernate's
    `IllegalStateException`, which became 500), so 503 also covers a database that is gone.
- `MlConfig.rest` is optional: default `0.0.0.0:50303` with the WEB ML origins. `config/ml-1.json`
  has an explicit `rest` block. `MlService` starts REST last and stops it first. `/status` adds
  storage counters (stored anomalies/predictions, recorded, failed writes, queued) to the RMI
  status. `/meta` lists the data types (histograms, seen and chain types) and the chains with
  their data types.
- `test_support.RestTestClient` (JDK `HttpClient`). `StorageFixtures` is public for tests in
  other packages.
- `intgrtn_tests/msml/MlTestSystem`: ML with two fake fogs, RMI and REST on free ports, and a
  restart on the same config/database. `MlServiceIT` uses it.
- Tests:
  - Unit: `FilterParserTest` (16), `ResultServiceTest` (4), `RestApiTest` (10: real server on
    port 0, paging, 404/400/415/405, comments, deletes, live endpoints, CORS, 503).
  - IT: `MlRestApiIT` (4: IT-REST steps 1–9, H2 file in a temp directory) and `MlPersistenceIT`
    (IT-PERSIST: comment survives restart, empty RMI log with a new epoch, new anomaly stored with
    the new epoch, no duplicates).
  - 318 tests in total (289 unit, 29 IT).
- Real run (`start-all.sh`): `/status`, `/meta`, `/predictions/current` and `/predictions` were
  served from the running system. The run also showed a 400 problem for `limit=0`, a CORS
  preflight allowed for `http://127.0.0.1:50400`, and a comment on a stored prediction (201 with
  `Location`) found with `commented=true`.

**Step 13 – done.**

- `web_ml`:
  - `WebMlConfig`: `bindHost` defaults to `0.0.0.0` and `refreshIntervalMs` to 5000 (allowed range
    1 s–1 h). Endpoint names must be unique, and `restUrl` must be an absolute http(s) URL; a
    trailing slash is removed.
  - `StaticFiles` serves classpath `web/`. A path is only served if every segment is a plain name
    (no `..`, no hidden files, no empty segments) and the file type is known (html, js, css, svg,
    png, ico, md). Directories and anything else are 404.
  - `WebMlService` uses the JDK `HttpServer` with 4 threads and answers GET and HEAD only
    (anything else is 405 with `Allow`). `/config.json` is generated from the config.
  - Every response sets `Cache-Control: no-cache`, `nosniff`, `Referrer-Policy: no-referrer` and
    a Content-Security-Policy: scripts and styles only from this server, `connect-src` only to the
    configured ML origins, no framing.
- UI (`index.html`, `style.css`, `app.js`, `favicon.svg`; Chart.js 4.5.1 vendored with its MIT
  license in `vendor/`):
  - Light and dark mode.
  - The x axis is linear in milliseconds, so no date adapter is needed.
  - Charts show the most likely value as a stepped line with the bin as a filled band, and
    anomalies as red crosses with a tooltip (chain, expected, observed). The line starts at the
    last prediction before the period and runs to the end of the period.
  - Live view refreshes every `refreshIntervalMs`: cards, charts of the last hour, the latest 10
    anomalies (linked to their details).
  - History: filter form, paged table (25 rows), charts of the filter period (last 24 h if no
    "From"), details panel with all fields, comments (add/edit/delete), delete one, delete all
    matching (disabled without a filter, shows the total and asks first).
  - The state is in the URL only (view, endpoint, kind, filter, sort, offset, id) via
    `pushState`/`popstate`, so reload and bookmarks keep it.
  - Elements are built with an `el()` helper that sets text via `textContent`; no `innerHTML`
    anywhere.
  - ML unreachable or 503: a banner with a countdown to the next retry; the last data stays on
    screen. After recovery the current view reloads.
  - 404 on details or comments shows "was deleted" and reloads; a 400 shows the problem detail
    next to the filter form.
- `MlTestSystem` (intgrtn_tests) is public and has `restPort()`, so ITs in other packages can use
  it.
- Tests:
  - Unit: `StaticFilesTest` (24: content types, traversal, hidden files, directories, unknown
    types), `WebMlConfigTest` (4, including the shipped `config/web-ml.json`), `WebMlServiceTest`
    (4: `config.json`, security headers, HEAD, 404/405 including `%2e%2e`).
  - IT: `WebMlIT` (5: IT-WEB 1–5). Step 4 also checks the browser's CORS preflight for a JSON
    POST. Step 5 guards the UI sources: no browser storage, cookies, `innerHTML`/`outerHTML`/
    `insertAdjacentHTML`, `document.write` or `eval`; no inline scripts, handlers or styles in
    `index.html`.
  - 355 tests in total (321 unit, 34 IT).
- Manual check (demo layout + `web_ml.jar`, driven in headless Chrome with Playwright from the
  scratchpad, not part of the build) covered:
  - live cards and charts;
  - a history filter that is reflected in the URL and kept on reload;
  - details, and a comment with `<script>` rendered as text, then edited and deleted;
  - a bad period showing a 400 next to the form;
  - stopping ML: banner with countdown, the last data stays; starting ML: the page recovers on
    its own.
  No JavaScript errors. The manual check list is in `implementation/README.md`.
- Left for step 14: start/stop/status of web_ml in the scripts and e2e checks of the REST API and
  the UI.

**Step 14 – done.**

- `common.sh`: new instance `web-ml` (`web_ml.jar`, `config/web-ml.json`). `ALL_INSTANCES` now
  has 10 entries. Also added `REST_URL`, `WEB_URL`, `DATA_DIR` and a `probe` helper (curl,
  2 s). `require_jars` also checks `web_ml.jar`.
- `start-all.sh` starts WEB ML last and prints the WEB and REST URLs. `stop-all.sh` stops it
  first.
- `status.sh` also shows the REST API and WEB ML URLs (reachable or not) and the size of
  `run/data/`.
- New `reset-data.sh [--yes]`:
  - Lists and deletes the H2 files in `run/data/` after asking.
  - Refuses while ML runs (H2 holds a file lock, and ML would recreate the files).
- `e2e-test.sh` has a new step 4 (REST and WEB, with curl):
  - this run's anomaly is stored (filter `from` = test start, so older data in `run/data/`
    does not count);
  - comment round trip: POST 201 with `Location`, GET, PUT 200, DELETE 204, list empty;
  - `/predictions/current` has both data types;
  - `limit=0` gives a 400 with `application/problem+json`;
  - the CORS preflight from the WEB ML origin is allowed;
  - WEB ML serves `/`, and its `/config.json` points to the REST URL.
  The old steps 4 and 5 became 5 and 6; step 6 also checks that the stored anomaly is still
  served after the ML restart.
- READMEs:
  - Root: WEB ML and REST in the overview, the web UI in the quick start.
  - Implementation: 10 instances, the REST and WEB ports, `reset-data.sh`, an "ML REST API"
    section with curl examples and the filter/paging parameters, and the updated e2e
    description.
- e2e run: `scripts/e2e-test.sh --build` → E2E PASSED. The drop was reported 201 s after start,
  and all 11 new REST/WEB checks passed.

**Step 15 – done.**

- `intgrtn_tests/system`:
  - `TestConfigs` builds the minimum layout on free ports of 127.0.0.1 (one CF scan range for
    the 2 fogs and 6 devices): constant values, 250 ms publish interval, 1 s fog windows, the
    short `temp-sudden-drop` chain, ML with an H2 file in a temp directory, REST and RMI.
  - `RunningSystem` starts everything in the `start-all.sh` order (ML, fogs, devices, WEB ML)
    and stops it in reverse.
  - The anomaly is deterministic: only `iot-temp-1` injects (-12 °C for 10 s, probability 0.02).
    Seed 273 makes the first injection fall on the 76th reading (≈19 s), found with a small
    Java program against `new Random(seed ^ 0x5DEECE66D)` like `ValueGeneratorFactory`. The
    fog then averages (23 + 23 + 11) / 3 = 19 °C, and ML indeed reports "observed 19.0".
  - `FullSystemIT` (IT-E2E):
    - both fogs aggregate 3 devices (checked with a UDP subscriber);
    - RMI and REST status agree, and both fogs are ACTIVE;
    - `MlClient.runOnce` prints the anomaly and predictions of both types;
    - the anomaly is stored and can be commented over REST;
    - WEB ML's `config.json` points to this ML;
    - every service is still RUNNING.
    About 21 s, passed 7 runs in a row.
- Test review (11 §4):
  - New `Waiting.holdsFor(duration, condition)` (Awaitility `during`) for negative checks: it
    fails as soon as the condition breaks instead of sleeping and checking once. Used in
    `MlServiceIT` (no new events; exactly one prediction change in steps 5 and 6),
    `IotDeviceIT` (stays RUNNING while the sink is down) and `CfOverlayIT` (no extra
    deliveries, no duplicates over a cycle, nothing beyond TTL).
  - `FogNodeIT` lease expiry: instead of fixed 2.5 s + 2.2 s pauses it waits until the expired
    subscriber gets no aggregate for two windows, then checks that the active one still
    receives.
  - `SensorPublisherTest` uses Awaitility `during`. `AbstractMicroServiceTest` uses Awaitility
    instead of a hand-written poll loop; `common` got Awaitility as a test dependency.
  - Remaining `Waiting.pause` calls only pace test data (rounds of aggregates, publish bursts)
    or wait out ML's resubscription before replaying old-epoch datagrams.
  - Planned `test_support` tests: `PortsTest` (2), `RestTestClientTest` (3, against a JDK
    echo server), `WaitingTest` (2).
- 363 tests in total (328 unit, 35 IT). The integration tests passed 3 times in a row.

**Step 16 – done.**

- New `uml/ms_ml_results.puml` for the packages `storage`, `results` and `rest`:
  - entities (single-table inheritance, embedded phases, comments), the repository and its
    JPA implementation, the recorder as event-log listener;
  - `ResultService`;
  - `RestServer` with its controllers, parser, mapper and resources.
  It is a separate diagram because `ms_ml.puml` is already very wide. `ms_ml.puml` links to
  it: `MlConfig.storage/rest`, `MlService` (`restStatus()`, `meta()`, `restPort()`,
  composition of Database, ResultRecorder, ResultService, RestServer), `EventLog.addListener`.
- New `uml/web_ml.puml`: the Java classes, plus the browser files as artifacts
  (`allowmixing`), the browser and the ML REST API as nodes, and the CSP/URL-state rules as
  notes. No `intgrtn_tests` diagram (optional in the plan, left out).
- `check-uml.sh`: a module's types may be spread over several diagrams (`ms_ml` → `ms_ml.puml`
  + `ms_ml_results.puml`); `web_ml` is checked too. Verified that a bogus type and a bogus
  operation in a diagram are reported.
- `render-uml.sh` sets `PLANTUML_LIMIT_SIZE=8192`. PlantUML crops at 4096 px by default, which
  had cut off `common.png` (5460 px wide) and `ms_ml.png` (7283 px) since the first steps;
  all PNGs are now complete.
- Final system run (`start-all.sh --demo`, headless Chrome on `http://127.0.0.1:50400/`):
  - the injected temperature drop was stored at 15:25:08 and shown as a red cross in the live
    temperature chart and in "Latest anomalies";
  - opened from that list, commented (comment count 1 in the table), then deleted in the
    details panel ("Deleted anomaly #62.", the panel closes, the table shows the rest);
  - no JavaScript errors.
- The plan of the change request (steps 9–16) is complete.
