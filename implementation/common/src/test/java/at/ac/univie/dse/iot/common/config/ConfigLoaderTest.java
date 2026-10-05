package at.ac.univie.dse.iot.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.DataType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    record SensorSection(String kind, long publishIntervalMs) implements ValidatableConfig {
        @Override
        public void validate(ConfigValidator validator) {
            validator.notBlank("kind", kind).positive("publishIntervalMs", publishIntervalMs);
        }
    }

    record Endpoint(String host, int port) implements ValidatableConfig {
        @Override
        public void validate(ConfigValidator validator) {
            validator.notBlank("host", host).port("port", port);
        }
    }

    record TestConfig(String serviceId, DataType dataType, SensorSection sensor, List<Endpoint> endpoints)
            implements ServiceConfig {
        @Override
        public void validate(ConfigValidator validator) {
            validator.notBlank("serviceId", serviceId)
                    .notNull("dataType", dataType)
                    .nested("sensor", sensor)
                    .each("endpoints", endpoints);
        }
    }

    private static final String VALID = """
            {
              "serviceId": "iot-temp-1",
              "dataType": "temperature",
              "sensor": { "kind": "SINE", "publishIntervalMs": 1000 },
              "endpoints": [ { "host": "127.0.0.1", "port": 50201 } ]
            }
            """;

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void loadsValidConfigFromFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("iot-temp-1.json"), VALID);

        TestConfig config = loader.load(file, TestConfig.class);

        assertEquals("iot-temp-1", config.serviceId());
        assertEquals(DataType.of("temperature"), config.dataType());
        assertEquals(new SensorSection("SINE", 1000), config.sensor());
        assertEquals(List.of(new Endpoint("127.0.0.1", 50201)), config.endpoints());
    }

    @Test
    void missingFileIsReportedWithPath(@TempDir Path dir) {
        Path missing = dir.resolve("missing.json");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(missing, TestConfig.class));

        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertTrue(e.getMessage().contains("missing.json"), e.getMessage());
    }

    @Test
    void reportsAllValidationProblemsWithDottedPaths() {
        String json = """
                {
                  "serviceId": "",
                  "dataType": "temperature",
                  "sensor": { "kind": "SINE", "publishIntervalMs": 0 },
                  "endpoints": [ { "host": "a", "port": 1 }, { "host": "b", "port": 70000 } ]
                }
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, TestConfig.class, "test.json"));

        assertEquals(List.of(
                "serviceId must not be blank",
                "sensor.publishIntervalMs must be > 0",
                "endpoints[1].port must be between 1 and 65535"), e.problems());
        assertTrue(e.getMessage().startsWith("Invalid configuration in test.json:"), e.getMessage());
    }

    @Test
    void missingNestedSectionIsReported() {
        String json = """
                { "serviceId": "x", "dataType": "co2", "endpoints": [] }
                """;

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, TestConfig.class, "t"));

        assertEquals(List.of("sensor must be set"), e.problems());
    }

    @Test
    void unknownPropertyIsReportedWithItsPath() {
        String json = VALID.replace("\"publishIntervalMs\"", "\"publishIntervallMs\"");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, TestConfig.class, "t"));

        assertTrue(e.getMessage().contains("sensor.publishIntervallMs"), e.getMessage());
        assertTrue(e.getMessage().contains("line 4"), e.getMessage());
    }

    @Test
    void missingNumberIsReportedByValidation() {
        String json = VALID.replace(", \"publishIntervalMs\": 1000", "");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, TestConfig.class, "t"));

        assertEquals(List.of("sensor.publishIntervalMs must be > 0"), e.problems());
    }

    @Test
    void invalidValueRejectedByValueObjectIsReportedWithItsPath() {
        String json = VALID.replace("\"temperature\"", "\"air pressure\"");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, TestConfig.class, "t"));

        assertTrue(e.getMessage().contains("dataType"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid data type 'air pressure'"), e.getMessage());
    }

    @Test
    void wrongTypeIsReportedWithIndexedPath() {
        String json = VALID.replace("\"port\": 50201", "\"port\": \"fifty\"");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, TestConfig.class, "t"));

        assertTrue(e.getMessage().contains("endpoints[0].port"), e.getMessage());
    }

    @Test
    void malformedJsonIsReported() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> loader.parse("{ \"serviceId\": ", TestConfig.class, "broken.json"));

        assertTrue(e.getMessage().startsWith("Invalid configuration in broken.json"), e.getMessage());
    }

    @Test
    void emptyDocumentIsReported() {
        assertThrows(ConfigException.class, () -> loader.parse("", TestConfig.class, "empty.json"));
    }
}
