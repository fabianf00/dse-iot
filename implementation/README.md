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
| `ms_ml` | anomaly detection (behaviour chains) and prediction (histograms), RMI server | `ms_ml/target/ms_ml.jar` |
| `clnt_ml` | command line client, polls MS ML over RMI | `clnt_ml/target/clnt_ml.jar` |

## Build and test

```
./mvnw clean verify              # build all modules, run all unit and integration tests
./mvnw package -DskipTests       # only build the JARs
```

## Run the minimum layout (1 ML, 2 Fog, 6 IoT on 127.0.0.1)

```
scripts/start-all.sh [--demo] [--build]   # start all 9 instances in the background
scripts/client.sh                          # print anomalies and prediction changes (Ctrl+C to stop)
scripts/client.sh --once                   # print what is new and exit
scripts/client.sh --status                 # ML state and its fog subscriptions
scripts/status.sh                          # which instances are running
scripts/stop-all.sh                        # stop everything (graceful, SIGKILL after 10 s)
scripts/start-service.sh <instance>        # start / restart one instance, e.g. ml-1
scripts/stop-service.sh <instance>         # stop one instance
```

- Logs: `run/logs/<instance>.log`, PIDs: `run/pids/<instance>.pid` (both generated, safe to delete).
- `--demo` uses `config/demo/<instance>.json` where it exists: two devices inject anomalies about
  3 minutes after start instead of rarely (seeded, so it is reproducible).
- Single instance by hand: `java -jar ms_iot/target/ms_iot.jar config/iot-temp-1.json`.

| Instance | Config | Ports |
|---|---|---|
| ml-1 | `config/ml-1.json` | UDP 50301 (aggregates), RMI registry 1099, RMI object 50302 |
| fog-temp / fog-co2 | `config/fog-temp.json`, `config/fog-co2.json` | CF TCP 50121 / 50122, UDP 50201 / 50202 |
| iot-temp-1..3 | `config/iot-temp-*.json` | CF TCP 50101–50103 |
| iot-co2-1..3 | `config/iot-co2-*.json` | CF TCP 50111–50113 |
| client | `config/clnt-ml.json` | – |

CF peers find each other by scanning `127.0.0.1:50100–50129`.

## End-to-end test

```
scripts/e2e-test.sh [--build]    # about 4–5 minutes, prints PASS/FAIL per check
```

Starts the demo layout, checks that the fogs aggregate 3 devices each and ML is subscribed,
runs the client with `--once` until the injected temperature drop is reported (no event printed
twice), kills ML under a polling client, restarts it, and checks that nothing is left running.

## UML

`uml/*.puml` (one diagram per artifact) and rendered `uml/*.png`.

```
scripts/check-uml.sh             # diagrams vs. code: types in both directions, operation names
PLANTUML_JAR=/path/plantuml.jar scripts/render-uml.sh   # re-render the PNGs
```
