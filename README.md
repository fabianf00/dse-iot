# (Simulated) Internet of Things Cloud

A distributed system of Java microservices that simulates an IoT cloud:

- **IoT devices** publish sensor readings over a self-built peer-to-peer **Communication
  Framework** (plain TCP, peer discovery by scanning, flooding with TTL and duplicate detection).
- **Fog nodes** aggregate the readings per time window and push the aggregates over UDP.
- **MS ML** detects anomalies with behaviour chains and predicts values with histograms. It
  serves the results to a command line client over Java RMI, stores them (JPA/Hibernate, H2)
  and offers a REST API to load, filter, delete and comment them.
- **WEB ML** is a web UI with charts of current and historic results, built on the REST API.

## Source

The project is based on the semester project of the course **052500 VU Distributed Systems
Engineering** (winter term 2021, **University of Vienna**). The assignment came in two phases:
DESIGN, and FINAL with a change request. The assignment documents are not part of this
repository.

This is a personal project for fun and learning. It is not an official or graded solution of
the course.

## Contents

| Folder | Content |
|---|---|
| [`implementation/`](implementation/README.md) | Maven multi-module project: code, tests, configs, start/stop scripts, UML diagrams |
| [`planning/`](planning/README.md) | Design and implementation plan, including the plan for the FINAL change request |

## Quick start

Requires Java 17 or newer (Maven comes with the wrapper).

```
cd implementation
./mvnw clean verify               # build and run all tests
scripts/start-all.sh --demo       # start 1 ML, 2 fog, 6 IoT instances and the web UI
open http://127.0.0.1:50400/      # web UI (anomalies about 3 min after start in demo mode)
scripts/client.sh                 # or watch the events in the terminal (Ctrl+C to stop)
scripts/stop-all.sh
```

See [`implementation/README.md`](implementation/README.md) for details.
