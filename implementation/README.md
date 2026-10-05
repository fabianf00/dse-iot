# (Simulated) IoT Cloud – Implementation

Java 17+, Maven (via the included wrapper, no installation needed). All commands run in this directory.

## Modules

| Module | What | Runnable JAR |
|---|---|---|
| `common` | config loading/validation, JSON, models, Fog Aggregate API messages, service lifecycle | – |
| `cf` | Communication Framework: P2P over plain TCP (scanning, flooding with TTL, duplicate detection) | – |
| `ms_iot` | simulated IoT device, publishes sensor readings through the CF | `ms_iot/target/ms_iot.jar` |
| `ms_fog` | fog node, aggregates one data type per window, serves aggregates over UDP | `ms_fog/target/ms_fog.jar` |
| `ml_api` | Java RMI API of MS ML (shared by MS ML and the client) | – |
| `ms_ml` | anomaly detection (behaviour chains) and prediction (histograms), RMI server, result history (H2/JPA) and REST API | `ms_ml/target/ms_ml.jar` |
| `clnt_ml` | command line client, polls MS ML over RMI | `clnt_ml/target/clnt_ml.jar` |
| `web_ml` | web UI for current and historic ML results (static files + Chart.js, talks to the ML REST API) | `web_ml/target/web_ml.jar` |
| `test_support` | shared test helpers: free ports, waiting, CF test peer, fake fog and ML services, UDP and RMI test helpers | – |
| `intgrtn_tests` | all integration tests (`*IT`), run by maven-failsafe | – |

## Build and test

```
./mvnw clean verify              # build all modules, run unit tests and integration tests
./mvnw clean verify -DskipITs    # unit tests only (integration tests are skipped)
./mvnw test                      # unit tests only
./mvnw package -DskipTests       # only build the JARs
```

Unit tests live in each module (`src/test`). Integration tests live in `intgrtn_tests` and keep the
package of the code they test, so they can use package-private test hooks.

## Run the minimum layout (1 ML, 2 Fog, 6 IoT, WEB ML on 127.0.0.1)

```
scripts/start-all.sh [--demo] [--build]   # start all 10 instances in the background
scripts/client.sh                          # print anomalies and prediction changes (Ctrl+C to stop)
scripts/client.sh --once                   # print what is new and exit
scripts/client.sh --status                 # ML state and its fog subscriptions
scripts/status.sh                          # which instances are running, REST/WEB URLs, ML status
scripts/stop-all.sh                        # stop everything (graceful, SIGKILL after 10 s)
scripts/start-service.sh <instance>        # start / restart one instance, e.g. ml-1
scripts/stop-service.sh <instance>         # stop one instance
scripts/reset-data.sh [--yes]              # delete the stored ML results (ML must be stopped)
```

Then open the web UI at http://127.0.0.1:50400/.

- Logs: `run/logs/<instance>.log`, PIDs: `run/pids/<instance>.pid` (both generated, safe to delete).
- ML stores anomalies, predictions and comments in an H2 file database, `run/data/ml-1.mv.db`.
  It survives restarts; `scripts/reset-data.sh` deletes it.
- `--demo` uses `config/demo/<instance>.json` where it exists: two devices inject anomalies about
  3 minutes after start instead of rarely (seeded, so it is reproducible).
- Single instance by hand: `java -jar ms_iot/target/ms_iot.jar config/iot-temp-1.json`.

| Instance | Config | Ports |
|---|---|---|
| ml-1 | `config/ml-1.json` | UDP 50301 (aggregates), RMI registry 1099, RMI object 50302, REST 50303 |
| web-ml | `config/web-ml.json` | HTTP 50400 |
| fog-temp / fog-co2 | `config/fog-temp.json`, `config/fog-co2.json` | CF TCP 50121 / 50122, UDP 50201 / 50202 |
| iot-temp-1..3 | `config/iot-temp-*.json` | CF TCP 50101–50103 |
| iot-co2-1..3 | `config/iot-co2-*.json` | CF TCP 50111–50113 |
| client | `config/clnt-ml.json` | – |

CF peers find each other by scanning `127.0.0.1:50100–50129`.

## ML REST API

Base URL `http://127.0.0.1:50303/api/v1` (full description: `planning/09-ms-ml-storage-and-rest.md`).
Errors are answered with problem details (`application/problem+json`).

```
B=http://127.0.0.1:50303/api/v1
curl "$B/status"                                         # RMI status plus storage counters
curl "$B/meta"                                           # data types and behaviour chains
curl "$B/predictions/current"                            # live predictions (not stored)
curl "$B/anomalies?dataType=temperature&limit=10"        # newest first, paged
curl "$B/predictions?from=2026-10-05T12:00:00Z&sort=occurredAt,asc"
curl "$B/anomalies?commented=true&chainId=temp-sudden-drop"
curl "$B/anomalies/12"
curl -X POST -H 'Content-Type: application/json' -d '{"text":"window open","author":"me"}' "$B/anomalies/12/comments"
curl -X PUT  -H 'Content-Type: application/json' -d '{"text":"window was open"}' "$B/comments/3"
curl -X DELETE "$B/comments/3"
curl -X DELETE "$B/anomalies/12"
curl -X DELETE "$B/predictions?dataType=co2&to=2026-10-05T12:00:00Z"   # bulk delete needs a filter
```

Filters: `dataType` (repeatable), `chainId` (anomalies), `from`, `to` (ISO-8601 instants),
`commented=true|false`. Paging: `limit` (1–500, default 50), `offset`, `sort=occurredAt,desc|asc`.

## WEB ML

Started by `start-all.sh`, or by hand:

```
java -jar web_ml/target/web_ml.jar config/web-ml.json    # then open http://127.0.0.1:50400/
```

The browser calls the ML REST API (`http://127.0.0.1:50303/api/v1`) directly, so ML must be
running and allow the UI's origin (`rest.allowedOrigins` in `config/ml-1.json`). WEB ML itself
only serves the static UI and `/config.json`.

- **Live:** current prediction per data type, prediction history of the last hour (most likely
  value as a step line, bin as a band, anomalies as red crosses), latest anomalies.
- **History:** filter (kind, data types, chain, period, commented), paged table, chart of the
  period, details with comments (add/edit/delete), delete one or all matching.
- The filter and the selected result live in the URL (bookmarkable). Nothing is stored in the
  browser.

Manual check list:

1. The live charts update every 5 s while the demo layout runs.
2. Set a filter in History, reload the page: the filter and the results are the same.
3. Add, edit and delete a comment. A comment with `<script>` shows as plain text.
4. Delete one result. Set a filter and use "Delete all matching". Without a filter the button is
   disabled.
5. Stop ML (`scripts/stop-service.sh ml-1`): a banner "unreachable, retrying in N s" appears and
   the last data stays. Start ML again: the banner disappears without a reload.

## End-to-end test

```
scripts/e2e-test.sh [--build]    # about 4–5 minutes, prints PASS/FAIL per check
```

Starts the demo layout, checks that the fogs aggregate 3 devices each and ML is subscribed,
runs the client with `--once` until the injected temperature drop is reported (no event printed
twice). Then it checks with curl that the anomaly is stored, makes a comment round trip
(add, edit, delete), checks the live endpoints, problem details, CORS and that WEB ML serves
the UI and its `config.json`. Finally it kills ML under a polling client, restarts it, checks
that the stored anomaly is still served, and that nothing is left running. Needs `curl`.

## UML

`uml/*.puml` (one diagram per artifact) and rendered `uml/*.png`.

```
scripts/check-uml.sh             # diagrams vs. code: types in both directions, operation names
PLANTUML_JAR=/path/plantuml.jar scripts/render-uml.sh   # re-render the PNGs
```
