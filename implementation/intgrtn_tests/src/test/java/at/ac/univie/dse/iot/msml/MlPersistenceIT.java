package at.ac.univie.dse.iot.msml;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IT-PERSIST: stored results and comments survive a restart of ML; the RMI event log does not. */
class MlPersistenceIT {

    @TempDir
    Path dataDir;

    private MlTestSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    @Test
    void historySurvivesARestart() throws Exception {
        system = MlTestSystem.start("jdbc:h2:file:" + dataDir.resolve("ml-test").toAbsolutePath());

        // 1. An anomaly with a comment, then ML stops.
        system.produceTemperatureDrop();
        waiting().until(() -> anomalies().get("total").asInt() == 1);
        JsonNode first = anomalies().get("items").get(0);
        long firstEpoch = first.get("serverEpoch").asLong();
        assertEquals(201, system.rest().post("/anomalies/" + first.get("id").asLong() + "/comments",
                Map.of("text", "window open")).status());
        long predictions = system.rest().get("/predictions").json().get("total").asLong();
        system.stopMl();

        // 2. A new instance on the same database still has the anomaly and its comment,
        //    but its in-memory event log starts empty with a new epoch.
        system.startMl();
        JsonNode restored = anomalies().get("items").get(0);
        assertEquals(first.get("id").asLong(), restored.get("id").asLong());
        assertEquals("window open", restored.get("comments").get(0).get("text").asText());
        assertEquals(predictions, system.rest().get("/predictions").json().get("total").asLong());
        assertTrue(system.rmi().pollEvents(0, 100).events().isEmpty());
        long secondEpoch = system.rmi().getStatus().serverEpoch();
        assertNotEquals(firstEpoch, secondEpoch);

        // 3. Another anomaly is stored with the new epoch; nothing is duplicated.
        system.produceTemperatureDrop();
        waiting().until(() -> system.rmi().pollEvents(0, 100).events().stream().anyMatch(AnomalyDto.class::isInstance));
        waiting().until(() -> anomalies().get("total").asInt() == 2);
        JsonNode newest = anomalies().get("items").get(0);
        assertEquals(secondEpoch, newest.get("serverEpoch").asLong());
        Set<String> keys = new HashSet<>();
        for (JsonNode prediction : system.rest().get("/predictions?limit=500").json().get("items")) {
            assertTrue(keys.add(prediction.get("serverEpoch").asText() + "/" + prediction.get("eventSequence").asText()),
                    "duplicate " + prediction);
        }
    }

    private JsonNode anomalies() {
        return system.rest().get("/anomalies").json();
    }
}
