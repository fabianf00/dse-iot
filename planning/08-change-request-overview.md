# 08 – Change Request (FINAL): Overview

Source: `DSE_Project_2021W_FINAL.pdf`. The change request **extends** the DESIGN assignment:
everything in 01–07 stays valid (CF, MS IoT, MS Fog, MS ML, CLNT ML and its RMI API). This
file and 09–12 describe only what is new or what changes. Earlier files are not edited; where
this plan changes them, that is listed in §5.

As before, only the implementation is planned. The report (`report_final.pdf`) and the
GitLab/submission rules are out of scope.

## 1. New requirements → plan

| Requirement (FINAL) | Plan | Details |
|---|---|---|
| MS ML: DESIGN MS ML, CLNT ML and RMI API still needed | Unchanged; the RMI API stays the live, cursor-based view | – |
| MS ML: store & manage predictions, anomalies and metadata (timestamps, manually added comments) with a database and object-relational mapping, **no explicit SQL** | Jakarta Persistence (JPA) with Hibernate ORM and an embedded H2 database. Schema generated from the entities. Queries with the JPA **Criteria API** only (no SQL, no JPQL strings) | 09 §2 |
| MS ML: REST API to load, filter, delete and comment stored predictions and anomalies | REST API `/api/v1` on MS ML (Javalin): list with filters and paging, get, delete (single and filtered), comments (add, edit, delete), plus live endpoints (current predictions, status) | 09 §3 |
| WEB ML: web-based **graphical** visualization/management of current and historic ML results | New module `web_ml`: static single-page UI with charts (Chart.js). Live view (current predictions, recent history, anomalies as chart markers) and history view (filter, table, comments, delete) | 10 |
| WEB ML: display, filter, delete, comment via MS ML's REST API | The browser calls the MS ML REST API directly (CORS enabled on MS ML) | 10 §4 |
| WEB ML: minimal UI layer, hardly any logic, no data storage | Filtering, paging and sorting happen on the server. The UI only renders. A tiny static file server provides the UI and a `config.json` with the REST URLs. No browser storage | 10 §2 |
| Testing: unit and integration tests; implement the DESIGN test scenarios, adapt if needed | Unit tests stay in each module and get added for new code. All integration tests move to a separate project `intgrtn_tests`, with shared helpers in `test_support` | 11 |
| Integration tests must cover the new REST API and the DESIGN APIs | New scenarios IT-REST, IT-PERSIST, IT-WEB and an in-process IT-E2E, next to the moved IT-CF/IoT/Fog/ML/CLNT | 11 §3 |
| Implementation folder must contain `cf`, `ms_iot`, `ms_fog`, `ms_ml`, `clnt_ml`, `web_ml`, `intgrtn_tests` | Two new modules `web_ml` and `intgrtn_tests`, plus `test_support` | 11 §1 |
| "Each student one unique unit test per MS and the CF" | Team rule, does not apply to a one-person project. Every MS and the CF already have unit tests, and new code gets its own | 11 §4 |

## 2. Updated system overview

```
                      CF (P2P over TCP)                               browser
 ┌───────────────────────────────────────────────────┐          ┌──────────────────┐
 │ IoT temp-1..3   IoT co2-1..3   Fog temp   Fog co2 │          │ WEB ML UI        │
 └──────────────────────────────────┬──────────┬─────┘          │ (HTML/JS/charts) │
                                    │UDP       │UDP             └──┬───────────┬───┘
                                    ▼          ▼                   │ static    │ REST/JSON
                               ┌──────────────────────┐            │ files     │ (CORS)
                               │        MS ML         │◄───────────┼───────────┘
                               │ jobs/workers, chains,│            │
                               │ histograms, EventLog │      ┌─────┴──────┐
                               │   ├─ RMI API (live)  │      │  web_ml    │ static file
                               │   ├─ REST API (new)  │      │  server    │ server + config.json
                               │   └─ H2 DB via JPA   │      └────────────┘
                               └───────┬──────────────┘
                                       │ RMI
                                  ┌────┴────┐
                                  │ CLNT ML │
                                  └─────────┘
```

New data flow: every anomaly and prediction change that MS ML appends to its in-memory
`EventLog` is also **recorded** in the database. The RMI API keeps serving the in-memory log
(live polling, unchanged contract). The REST API serves the database (historic data that
survives restarts and can be filtered, commented and deleted), plus the live predictions and
status.

## 3. Technology decisions (options considered)

| # | Decision | Options | Choice and reason |
|---|---|---|---|
| F1 | ORM | JPA/Hibernate · Spring Data JPA · Ebean · jOOQ (not an ORM) | **JPA 3.2 with Hibernate ORM 7.4** (Java 17 baseline): standard API, no Spring needed. Fits our framework-free services and the existing `AbstractMicroService` lifecycle |
| F2 | Database | embedded H2 (file) · PostgreSQL server · SQLite | **H2 2.5, embedded, file-based** (`run/data/<serviceId>`): no extra server to deploy, data survives restarts. In-memory H2 for tests. PostgreSQL would only need another JDBC URL and driver |
| F3 | "No explicit SQL" | JPQL strings · Criteria API · Spring derived queries | **Criteria API** for all queries, plus `EntityManager.find/persist/remove`. Schema via Hibernate schema management (`hibernate.hbm2ddl.auto=update`), so no DDL is written by hand either |
| F4 | REST framework | JDK `HttpServer` · Javalin · Spring Boot · Jakarta REST (Jersey) | **Javalin 7.2** (Jetty 12): small, explicit routing, Jackson JSON, CORS plugin, no annotations magic. Spring Boot would replace our lifecycle and DI for little gain. JDK `HttpServer` would mean hand-written routing, CORS and error mapping |
| F5 | WEB ML technology | static HTML/JS · server-side templates (Thymeleaf) · React/Vue with a build toolchain | **Static HTML + vanilla JS + Chart.js 4.5 (vendored, MIT)**: no build step and no Node toolchain, and the minimal-logic rule is easy to keep |
| F6 | WEB ML ↔ REST | browser calls MS ML directly (CORS) · web_ml proxies `/api` | **Direct with CORS**: web_ml stays a pure static server. The allowed origins are configured on MS ML |
| F7 | Delete semantics | hard delete · soft delete (flag) | **Hard delete** with cascade to the comments: "delete" means gone, and the semantics are simple. A soft delete would need extra filters everywhere |
| F8 | Recording into the DB | synchronous in the worker · ML job · dedicated writer thread | **Dedicated single-thread writer** with a bounded queue, fed by an `EventLog` listener. Workers never wait on the DB, and persistence jobs are never dropped by job-queue backpressure |
| F9 | Integration test location | in each module · separate project | **Separate Maven module `intgrtn_tests`** (required), with shared helpers in `test_support`. The ITs keep their package names, so package-private test hooks keep working |

## 4. New and changed modules, ports and files

| Module | Status | Contents |
|---|---|---|
| `ms_ml` | extended | packages `msml.storage` (entities, repository, recorder), `msml.results` (use cases), `msml.rest` (REST API). Config blocks `storage` and `rest` |
| `web_ml` | **new** | `WebMlService` (static file server and `config.json`), static UI under `src/main/resources/web/` |
| `test_support` | **new** | shared test helpers (ports, await, fakes for fog/ML, CF test peer, REST client, config factories) |
| `intgrtn_tests` | **new** | all integration tests (moved and new), run by maven-failsafe |
| others | unchanged | except for moving their `*IntegrationTest` classes to `intgrtn_tests` |

| Instance | New port | Purpose |
|---|---|---|
| ml-1 | **TCP 50303** | REST API `http://<host>:50303/api/v1` |
| web-ml | **TCP 50400** | WEB ML UI `http://<host>:50400/` |

New or changed config files: `config/ml-1.json` (+ `storage`, `rest`), `config/web-ml.json` (new).

## 5. Changes to earlier planning files (not edited there)

| File | What changes |
|---|---|
| 01-architecture | Module list (+ `web_ml`, `test_support`, `intgrtn_tests`), tech stack (+ Hibernate, H2, Javalin, Chart.js, failsafe), ports (+ 50303, 50400), deployment (+ web_ml, DB files under `run/data`, scripts start processes with `implementation/` as working directory). Decision log: F1–F9 above |
| 02-communication-framework | No change |
| 03-ms-iot, 04-ms-fog | No functional change. Their integration tests move to `intgrtn_tests` |
| 05-ms-ml-and-clnt | MS ML gets storage and REST (see 09). `EventLog` gets listeners. `ServiceStatusDto` content is reused for `GET /status`. The RMI API and CLNT ML are unchanged |
| 06-integration-testing | Scenarios move to `intgrtn_tests` and new ones are added (see 11). The e2e script gets REST and web checks |
| 07-implementation-order | Continued by steps 9–16 in 12 |
