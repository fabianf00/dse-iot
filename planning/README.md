# DSE 2021W – (Simulated) IoT Cloud – Implementation Plan

Plan for implementing the Communication Framework (CF), the microservices MS IoT, MS Fog and
MS ML, and the client CLNT ML, as described in `DSE_Project_2021W_DESIGN.pdf`.

## Index

| File | Content |
|---|---|
| [01-architecture.md](01-architecture.md) | Tech stack, module layout, shared design, network layout, ports, deployment, design decisions |
| [02-communication-framework.md](02-communication-framework.md) | CF: P2P design, threading, wire format, protocol, error cases, MS integration API, UML draft |
| [03-ms-iot.md](03-ms-iot.md) | MS IoT: sensor simulation, SIMI configs, published messages, UML draft |
| [04-ms-fog.md](04-ms-fog.md) | MS Fog: windowed aggregation, UDP subscribe/callback protocol, UML draft |
| [05-ms-ml-and-clnt.md](05-ms-ml-and-clnt.md) | MS ML: job/worker design, anomaly chains, histogram prediction, RMI API, and CLNT ML |
| [06-integration-testing.md](06-integration-testing.md) | One integration test scenario per MS (plus CF and end-to-end) |
| [07-implementation-order.md](07-implementation-order.md) | Build order per module, tests per step, technical risks, open questions |

## System at a glance

```
            CF (P2P over plain TCP, flooding + TTL + dedupe)
 ┌───────────────────────────────────────────────────────────────┐
 │ IoT temp-1  IoT temp-2  IoT temp-3                            │
 │ IoT co2-1   IoT co2-2   IoT co2-3      Fog temp (AVG) ────────┼──UDP──┐
 │                                        Fog co2  (MAX) ────────┼──UDP──┤
 └───────────────────────────────────────────────────────────────┘       │
                                                                  ┌──────▼──────┐
                                                                  │   MS ML     │ jobs/workers:
                                                                  │  (anomaly + │ anomaly chains,
                                                                  │  prediction)│ histograms
                                                                  └──────┬──────┘
                                                                         │ Java RMI (sync, polling)
                                                                  ┌──────▼──────┐
                                                                  │  CLNT ML    │ CLI
                                                                  └─────────────┘
```

| Component | Instances (minimum) | Talks via | Purpose |
|---|---|---|---|
| CF | library inside IoT and Fog | TCP P2P | Peer discovery by random scanning, flooding with TTL |
| MS IoT | 2 types × 3 instances (temperature, CO2) | CF | Publishes simulated sensor readings |
| MS Fog | 1 per data type (2) | CF in, UDP out | Windowed aggregation (avg/max/min) and push to subscribers |
| MS ML | 1 | UDP in, RMI out | Anomaly detection (behaviour chains) and prediction (histograms) |
| CLNT ML | 1 | RMI | Polls ML and prints new anomalies and prediction changes |

## Assumptions

1. **Full scope.** All components including CLNT ML and the RMI API in MS ML.
2. **Java 17 + Maven.** Java RMI is required, so Java is the natural choice for every module.
3. **Data types are temperature and CO2.** These are the ones in the assignment's example.
   A third type such as humidity only needs new config files, because the code is SIMI.
4. **Generated data.** Sensor values come from generators in code (sine wave, noise, injected
   anomalies), not from an external dataset.
5. **Shortened demo time windows.** The ML behaviour chains are defined in seconds in the
   config, so the demo uses short windows (tens of seconds).

## Implementation checklist

- [ ] `implementation/` with modules `cf`, `ms_iot`, `ms_fog`, `ms_ml`, `clnt_ml` (plus shared `common`, `ml_api`)
- [ ] Classes, interfaces, methods, return types and parameters for each module, with Javadoc
- [ ] One UML class diagram per artifact (CF, IoT, Fog, ML, CLNT), consistent with the code
- [ ] Unit tests per module
- [ ] Configs for the minimum layout (6 IoT, 2 Fog, 1 ML, 1 CLNT) and start/stop scripts
- [ ] Integration tests from 06-integration-testing.md
- [ ] Full system run locally and over the VPN

## Change request (FINAL)

The change request in `DSE_Project_2021W_FINAL.pdf` extends the plan above. The files above
stay as they are. The changes are described in:

| File | Content |
|---|---|
| [08-change-request-overview.md](08-change-request-overview.md) | New requirements → plan, updated system overview, technology decisions, new modules and ports, what changes in 01–07 |
| [09-ms-ml-storage-and-rest.md](09-ms-ml-storage-and-rest.md) | MS ML: historic storage with JPA/Hibernate/H2 (no explicit SQL), recording path, REST API v1 (endpoints, filters, examples, errors) |
| [10-web-ml.md](10-web-ml.md) | WEB ML: minimal static UI with charts, views, rules for "no logic, no storage", edge cases |
| [11-integration-tests.md](11-integration-tests.md) | Separate integration test project, shared test helpers, moved and new IT scenarios, new unit tests |
| [12-change-request-implementation-order.md](12-change-request-implementation-order.md) | Steps 9–16, risks, decisions to confirm |
