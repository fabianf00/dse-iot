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
