package at.ac.univie.dse.iot.msiot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.NodeId;
import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.msiot.generator.AnomalyInjectingGenerator;
import at.ac.univie.dse.iot.msiot.generator.ConstantGenerator;
import at.ac.univie.dse.iot.msiot.generator.RandomWalkGenerator;
import at.ac.univie.dse.iot.msiot.generator.SineWaveGenerator;
import at.ac.univie.dse.iot.msiot.generator.ValueGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class IotConfigTest {

    /** Instance configs shipped in implementation/config (tests run in the module directory). */
    private static final Path CONFIG_DIR = Path.of("..", "config");

    private final ConfigLoader loader = new ConfigLoader();

    private List<IotConfig> shippedConfigs() throws IOException {
        try (Stream<Path> files = Files.list(CONFIG_DIR)) {
            return files.filter(f -> f.getFileName().toString().startsWith("iot-"))
                    .sorted()
                    .map(f -> loader.load(f, IotConfig.class))
                    .toList();
        }
    }

    @Test
    void shippedConfigsAreValidAndCoverTwoTypesWithThreeInstancesEach() throws IOException {
        List<IotConfig> configs = shippedConfigs();

        Map<DataType, Long> instancesPerType = configs.stream()
                .collect(Collectors.groupingBy(c -> c.sensor().dataType(), Collectors.counting()));
        assertEquals(Map.of(DataType.of("temperature"), 3L, DataType.of("co2"), 3L), instancesPerType);
    }

    @Test
    void shippedConfigsHaveUniqueIdsAndPortsAndDefaultNodeIds() throws IOException {
        List<IotConfig> configs = shippedConfigs();

        Set<Integer> ports = new HashSet<>();
        for (IotConfig config : configs) {
            assertEquals(NodeId.of(config.serviceId()), config.cf().nodeId(), "cf.nodeId defaults to serviceId");
            assertTrue(ports.add(config.cf().listenPort()), "duplicate port " + config.cf().listenPort());
            assertTrue(config.cf().scan().portRange().from() <= config.cf().listenPort()
                    && config.cf().listenPort() <= config.cf().scan().portRange().to(), "own port is in the scan range");
        }
        assertEquals(configs.size(), configs.stream().map(IotConfig::serviceId).distinct().count());
    }

    @Test
    void factoryBuildsTheConfiguredGenerators() throws IOException {
        Map<String, IotConfig> byId = shippedConfigs().stream()
                .collect(Collectors.toMap(IotConfig::serviceId, Function.identity()));

        assertInstanceOf(AnomalyInjectingGenerator.class, ValueGeneratorFactory.create(byId.get("iot-temp-1").sensor()));
        assertInstanceOf(SineWaveGenerator.class, ValueGeneratorFactory.create(byId.get("iot-temp-2").sensor()));
        assertInstanceOf(RandomWalkGenerator.class, ValueGeneratorFactory.create(byId.get("iot-co2-3").sensor()));
        assertInstanceOf(ConstantGenerator.class, ValueGeneratorFactory.create(
                new SensorConfig(DataType.of("x"), at.ac.univie.dse.iot.common.model.Unit.PERCENT, 1,
                        GeneratorConfig.constant(1), null)));
    }

    @Test
    void sameSeedGivesTheSameValues() throws IOException {
        SensorConfig sensor = shippedConfigs().get(0).sensor();
        ValueGenerator first = ValueGeneratorFactory.create(sensor);
        ValueGenerator second = ValueGeneratorFactory.create(sensor);

        for (int i = 0; i < 100; i++) {
            Instant at = Instant.ofEpochSecond(1_600_000_000L + i);
            assertEquals(first.next(at), second.next(at));
        }
    }

    @Test
    void differentDevicesOfOneTypeProduceDifferentStreams() throws IOException {
        Map<String, IotConfig> byId = shippedConfigs().stream()
                .collect(Collectors.toMap(IotConfig::serviceId, Function.identity()));
        Instant at = Instant.ofEpochSecond(1_600_000_000L);

        assertNotEquals(ValueGeneratorFactory.create(byId.get("iot-temp-1").sensor()).next(at),
                ValueGeneratorFactory.create(byId.get("iot-temp-2").sensor()).next(at));
    }

    @Test
    void invalidConfigListsEveryProblem() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse("""
                {
                  "serviceId": "iot-broken",
                  "sensor": {
                    "dataType": "temperature",
                    "unit": "CELSIUS",
                    "publishIntervalMs": 0,
                    "generator": { "kind": "SINE", "base": 20, "amplitude": -1, "min": 10, "max": 5 },
                    "anomalyInjection": { "enabled": true, "probabilityPerReading": 2, "durationSeconds": 0 }
                  },
                  "cf": { "listenPort": 50101 }
                }
                """, IotConfig.class, "iot-broken.json"));

        assertEquals(List.of(
                "sensor.publishIntervalMs must be > 0",
                "sensor.generator.max must be >= min",
                "sensor.generator.amplitude must be >= 0",
                "sensor.generator.periodSeconds must be > 0",
                "sensor.anomalyInjection.probabilityPerReading must be > 0 and <= 1",
                "sensor.anomalyInjection.durationSeconds must be > 0"), e.problems());
    }

    @Test
    void unknownUnitIsReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse("""
                { "serviceId": "x", "sensor": { "dataType": "t", "unit": "KELVINS", "publishIntervalMs": 1,
                  "generator": { "kind": "CONSTANT" } }, "cf": { "listenPort": 1 } }
                """, IotConfig.class, "x.json"));

        assertTrue(e.getMessage().contains("sensor.unit"), e.getMessage());
    }
}
