# 03 – Microservice IoT

## 1. Responsibilities

- Simulate **one sensor per device** that produces a stream of values of one data type.
- **SIMI:** one implementation and one JAR. At least 2 device types × 3 instances. All
  differences live in config files.
- Publish every reading through the CF. All networking is the CF's job.

## 2. Instances (SIMI)

| Instance | Data type | Unit | Interval | Generator | Normal range | Notes |
|---|---|---|---|---|---|---|
| iot-temp-1 | temperature | CELSIUS | 1000 ms | sine 23 ± 2 °C, period 10 min, noise 0.2 | 21–25 °C | anomaly injection (0.15 % per reading, −12 °C for 60 s) |
| iot-temp-2 | temperature | CELSIUS | 1000 ms | sine 22.5 ± 1.5 °C, phase +150 s | 21–24 °C | |
| iot-temp-3 | temperature | CELSIUS | 2000 ms | random walk around 23 °C, clamp 15–35 | ~22–24 °C | |
| iot-co2-1 | co2 | PPM | 1000 ms | sine 500 ± 60 ppm, period 15 min, noise 10 | 440–560 ppm | anomaly injection (0.15 % per reading, +900 ppm for 90 s) |
| iot-co2-2 | co2 | PPM | 1000 ms | sine 480 ± 50 ppm, phase +300 s | 430–530 ppm | |
| iot-co2-3 | co2 | PPM | 2000 ms | random walk around 470 ppm, clamp 380–5000 | ~450–490 ppm | |

The normal ranges are chosen so the ML behaviour chains (05) hold in normal operation:
temperature stays above 20 °C, and CO2 (MAX of 3 devices) stays below 700 ppm. They break only on injected
anomalies. The temperature offset is large (−12 °C) because Fog averages 3 devices, so one
device's drop is diluted to a third.

Anomaly injection is deterministic per seed, so the ML anomaly chains can actually fire in demos
and tests.

### Example config `config/iot-temp-1.json`

```json
{
  "serviceId": "iot-temp-1",
  "sensor": {
    "dataType": "temperature",
    "unit": "CELSIUS",
    "publishIntervalMs": 1000,
    "generator": {
      "kind": "SINE",
      "base": 23.0, "amplitude": 2.0, "periodSeconds": 600, "phaseSeconds": 0,
      "noiseStdDev": 0.2, "seed": 101,
      "min": -20.0, "max": 50.0
    },
    "anomalyInjection": { "enabled": true, "probabilityPerReading": 0.0015, "offset": -12.0, "durationSeconds": 60 }
  },
  "cf": {
    "listenPort": 50101,
    "scan": { "hosts": ["127.0.0.1"], "portRange": { "from": 50100, "to": 50129 } }
  }
}
```

`cf.nodeId` may be omitted; it defaults to `serviceId`. `min`/`max` and `seed` are optional
(`null` = unbounded / random seed). `anomalyInjection` is optional.

## 3. Design

Packages: `msiot` (service, configs, factory, publisher), `msiot.generator` (value
generators, `ValueRange`), `msiot.sensor` (`Sensor`, `SimulatedSensor`).

- `IotDeviceService extends AbstractMicroService<IotConfig>` wires everything at startup.
- `ValueGenerator` (**Strategy**) has the implementations `SineWaveGenerator`,
  `RandomWalkGenerator` and `ConstantGenerator` (for tests). It is created by
  `ValueGeneratorFactory` from `generator.kind`.
  `CsvReplayGenerator` is optional, for replaying an existing dataset.
- `AnomalyInjectingGenerator` (**Decorator**) wraps any generator and adds the configured offset
  for a period of time.
- `SimulatedSensor` combines the generator, `DataType`, unit and a sequence counter, and
  produces `SensorReading`s.
- `SensorPublisher` is a `ScheduledExecutorService` task. Every `publishIntervalMs` it reads the
  sensor and calls `cf.publish("sensor." + dataType, reading)`. It never blocks, because CF
  publish is async.

### UML class diagram (draft → `uml/ms_iot.puml`)

```plantuml
@startuml ms_iot
package at.ac.univie.dse.iot.msiot {
  class IotDeviceApplication { +{static} main(args : String[]) : void }
  class IotDeviceService extends AbstractMicroService {
    -cf : CommunicationFramework
    -publisher : SensorPublisher
    #initialize(config : IotConfig) : void
    #startComponents() : void
    #stopComponents() : void
  }
  class IotConfig <<record>> { serviceId : String
    sensor : SensorConfig
    cf : CfConfig }
  class SensorConfig <<record>> { dataType : DataType
    unit : Unit
    publishIntervalMs : long
    generator : GeneratorConfig
    anomalyInjection : AnomalyInjectionConfig }
  interface Sensor { +read() : Optional<SensorReading> }
  class SimulatedSensor implements Sensor {
    -deviceId : String
    -dataType : DataType
    -unit : Unit
    -generator : ValueGenerator
    -range : ValueRange
    -clock : Clock
    -bootId : UUID
    -nextSequence : long
  }
  class ValueRange <<record>> { min : Double
    max : Double
    +clamp(v : double) : double }
  interface ValueGenerator { +next(at : Instant) : double }
  class SineWaveGenerator implements ValueGenerator
  class RandomWalkGenerator implements ValueGenerator
  class ConstantGenerator implements ValueGenerator
  class AnomalyInjectingGenerator implements ValueGenerator {
    -delegate : ValueGenerator
    -config : AnomalyInjectionConfig
    -random : Random
  }
  class ValueGeneratorFactory { +{static} create(s : SensorConfig) : ValueGenerator
    +{static} rangeOf(g : GeneratorConfig) : ValueRange }
  class SensorPublisher {
    -sensor : Sensor
    -cf : CommunicationFramework
    -topic : String
    -interval : Duration
    -scheduler : ScheduledExecutorService
    +start() : void
    +publishedCount() : long
    +stop() : void
    ~publishOnce() : void
  }
  IotDeviceService *-- SensorPublisher
  IotDeviceService --> CommunicationFramework
  SensorPublisher --> Sensor
  SensorPublisher --> CommunicationFramework
  SimulatedSensor --> ValueGenerator
  AnomalyInjectingGenerator o-- ValueGenerator
  ValueGeneratorFactory ..> ValueGenerator : creates
}
@enduml
```

## 4. Messages & communication

IoT only **publishes** through the CF. It ignores incoming data, but its CF node still forwards
it, because every device is a peer.

- **Topic:** `sensor.<dataType>`, e.g. `sensor.temperature`, `sensor.co2`
- **CF type:** `DATA` (envelope as in CF §4)
- **Payload `SensorReading`:**

```json
{
  "deviceId": "iot-temp-1",
  "dataType": "temperature",
  "unit": "CELSIUS",
  "value": 21.37,
  "sequence": 1042,
  "bootId": "b7e2c1d0-4b1a-4f3e-9c55-0a6f2d9e8a31",
  "measuredAt": 1637412185120
}
```

| Field | Type | Constraint |
|---|---|---|
| `deviceId` | string | equals `serviceId` |
| `dataType` | string | lowercase, matches topic suffix |
| `unit` | enum string | `CELSIUS`, `PPM`, … |
| `value` | double | finite (no NaN/Infinity), within the configured `min`/`max` |
| `sequence` | long | strictly increasing per device, starts at 0 on each start |
| `bootId` | UUID string | new on every device start (see Error case) |
| `measuredAt` | epoch ms | device clock, for information only |

**Consumers:** Fog instances subscribed to the topic. `sequence` lets them spot gaps (lost
readings) and log them.

### Use cases

- **Good:** temp-1 publishes every second. Through 3 links and flooding, the message reaches
  `fog-temp` within a few hops and is delivered once (dedupe).
- **Bad (no peers yet):** at startup the device has no connections. `publish` succeeds locally,
  the message is dropped, and a debug log is written. Acceptable for a live sensor stream: no
  buffering, so a later flood of stale data is avoided.
- **Bad (generator returns NaN or is out of range):** clamp to the range or skip the reading
  and log a warning. Invalid values are never published.
- **Bad (invalid config):** fail fast at startup with an explicit message, e.g.
  `sensor.publishIntervalMs must be > 0`.

### Error case (MS-specific)

**Restarted device resets `sequence` to 0.** A consumer that tracks sequences would read this
as massive reordering. Fix: consumers treat `sequence < lastSeen` together with a new
`measuredAt` epoch as a restart, not an error. Alternatively add `bootId` (UUID per start) to
the payload. **Decision: `bootId`** (cheap and unambiguous). Consumers reset their sequence
tracking whenever `bootId` changes.

## 5. Deployment

- `java -jar ms_iot.jar config/iot-<type>-<n>.json`. Six processes for the minimum setup.
- Reachable only through its CF TCP port (50101–50103, 50111–50113). No other API.
- Depends on no other MS. It only needs at least one CF peer for its data to go anywhere.
