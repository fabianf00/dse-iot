package at.ac.univie.dse.iot.msml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.msml.anomaly.BehaviourChain;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MlConfigTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void shippedConfigIsValid() {
        MlConfig config = loader.load(Path.of("..", "config", "ml-1.json"), MlConfig.class);

        assertEquals(2, config.fogNodes().size());
        assertEquals(3, config.anomaly().chains().size());
        assertEquals(2, config.prediction().histograms().size());
        assertEquals(1099, config.rmi().registryPort());
        assertEquals(50302, config.rmi().objectPort());
        assertEquals("MlService", config.rmi().bindName());
        assertEquals("jdbc:h2:file:./run/data/ml-1", config.storage().jdbcUrl());
        BehaviourChain ventilation = config.anomaly().chains().get(2);
        assertTrue(ventilation.involves(DataType.of("co2")) && ventilation.involves(DataType.of("temperature")),
                "one chain spans two data types");
        assertEquals(240, config.anomaly().maxSpanSeconds());
    }

    @Test
    void minimalConfigUsesDefaults() {
        MlConfig config = loader.parse(minimal(""), MlConfig.class, "ml.json");

        assertEquals(4, config.workers().threads());
        assertEquals(1_000, config.workers().queueCapacity());
        assertEquals(3_600, config.timeSeriesRetentionSeconds());
        assertEquals(10_000, config.events().retention());
        assertEquals(30_000, config.udp().leaseMs());
        assertEquals("127.0.0.1", config.rmi().hostname());
        assertEquals(2, config.anomaly().minSamplesPerPhase());
        assertEquals(500, config.prediction().histograms().get(0).maxSamples());
    }

    @Test
    void storageDefaultsToAFileDatabaseNamedAfterTheService() {
        MlConfig config = loader.parse(minimal(""), MlConfig.class, "ml.json");

        assertEquals("jdbc:h2:file:./run/data/ml-x", config.storage().jdbcUrl());
        assertEquals("sa", config.storage().user());
        assertEquals(10_000, config.storage().recorderQueueCapacity());
        assertEquals(5, config.storage().poolSize());
    }

    @Test
    void storageCanBeConfigured() {
        MlConfig config = loader.parse(minimal("\"storage\": { \"jdbcUrl\": \"jdbc:h2:mem:x\", \"poolSize\": 2 },"),
                MlConfig.class, "ml.json");

        assertEquals("jdbc:h2:mem:x", config.storage().jdbcUrl());
        assertEquals(2, config.storage().poolSize());
    }

    @Test
    void invalidStorageIsReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(
                minimal("\"storage\": { \"jdbcUrl\": \"h2:mem:x\", \"recorderQueueCapacity\": -1 },"),
                MlConfig.class, "ml.json"));

        assertEquals(List.of("storage.jdbcUrl must start with jdbc:", "storage.recorderQueueCapacity must be > 0"),
                e.problems());
    }

    @Test
    void retentionMustCoverTheLongestChain() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> loader.parse(minimal("\"timeSeriesRetentionSeconds\": 60,"), MlConfig.class, "ml.json"));

        assertEquals(1, e.problems().size());
        assertTrue(e.problems().get(0).startsWith("timeSeriesRetentionSeconds must cover the longest behaviour chain"));
    }

    @Test
    void invalidChainsAreReportedWithPaths() {
        String json = minimal("").replace("\"fromSec\": 20, \"toSec\": 10", "\"fromSec\": 10, \"toSec\": 20");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(json, MlConfig.class, "ml.json"));

        assertTrue(e.problems().contains("anomaly.chains[0].past.fromSec must be > toSec"), e.problems().toString());
    }

    private static String minimal(String extra) {
        return """
                {
                  "serviceId": "ml-x",
                  %s
                  "fogNodes": [ { "id": "fog-temp", "host": "127.0.0.1", "port": 50201 } ],
                  "udp": { "callbackPort": 50301 },
                  "anomaly": { "chains": [ {
                    "id": "c", "description": "d",
                    "past":    { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 20, "toSec": 10 },
                    "current": { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 10, "toSec": 0 },
                    "future":  { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "withinSec": 100 }
                  } ] },
                  "prediction": { "histograms": [ { "dataType": "temperature", "binWidth": 0.41 } ] }
                }
                """.formatted(extra);
    }
}
