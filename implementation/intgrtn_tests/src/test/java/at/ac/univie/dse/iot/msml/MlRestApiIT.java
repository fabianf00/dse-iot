package at.ac.univie.dse.iot.msml;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.MlEventDto;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.PredictionDto;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import at.ac.univie.dse.iot.testsupport.RestTestClient;
import at.ac.univie.dse.iot.testsupport.RestTestClient.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IT-REST: the ML REST API on stored results of a real ML instance (H2 file in a temp directory). */
class MlRestApiIT {

    private static final String WEB_ORIGIN = "http://127.0.0.1:50400";

    @TempDir
    Path dataDir;

    private MlTestSystem system;
    private RestTestClient rest;

    @BeforeEach
    void setUp() throws Exception {
        system = MlTestSystem.start("jdbc:h2:file:" + dataDir.resolve("ml-test").toAbsolutePath());
        rest = system.rest();
    }

    @AfterEach
    void tearDown() {
        system.close();
    }

    @Test
    void storedResultsCanBeFilteredCommentedAndDeleted() throws Exception {
        // 1. Normal values, then a temperature drop: the events are stored as they happen.
        system.produceTemperatureDrop();
        waiting().until(() -> system.rmi().pollEvents(0, 500).events().stream().anyMatch(AnomalyDto.class::isInstance));
        waiting().until(() -> system.rmi().getStatus().queueSize() == 0);
        List<MlEventDto> all = system.rmi().pollEvents(0, 500).events();
        long anomalyEvents = all.stream().filter(AnomalyDto.class::isInstance).count();
        long predictionEvents = all.stream().filter(PredictionChangeDto.class::isInstance).count();
        waiting().until(() -> total("/anomalies") == anomalyEvents && total("/predictions") == predictionEvents);
        assertEquals(1, anomalyEvents);
        assertTrue(predictionEvents >= 2, "temperature and co2 predictions, got " + predictionEvents);

        JsonNode anomaly = rest.get("/anomalies").json().get("items").get(0);
        long anomalyId = anomaly.get("id").asLong();
        assertEquals(MlTestSystem.CHAIN, anomaly.get("chainId").asText());
        assertEquals(14.0, anomaly.get("observedValue").asDouble());
        assertEquals(MlTestSystem.SERVICE_ID, anomaly.get("mlInstance").asText());
        Instant occurredAt = Instant.parse(anomaly.get("occurredAt").asText());

        // 2. Filters, paging and order.
        assertEquals(0, total("/anomalies?dataType=co2"));
        assertEquals(1, total("/anomalies?chainId=" + MlTestSystem.CHAIN));
        assertEquals(0, total("/anomalies?chainId=other"));
        assertEquals(1, total("/anomalies?from=" + occurredAt.minusSeconds(1) + "&to=" + occurredAt.plusSeconds(1)));
        assertEquals(0, total("/anomalies?from=" + occurredAt.plusSeconds(1)));
        assertEquals(1, total("/anomalies?commented=false"));
        long co2Predictions = total("/predictions?dataType=co2");
        assertTrue(co2Predictions >= 1);
        JsonNode oldestFirst = rest.get("/predictions?sort=occurredAt,asc").json().get("items");
        for (int i = 1; i < oldestFirst.size(); i++) {
            assertFalse(Instant.parse(oldestFirst.get(i).get("occurredAt").asText())
                    .isBefore(Instant.parse(oldestFirst.get(i - 1).get("occurredAt").asText())), "ascending order");
        }
        JsonNode second = rest.get("/predictions?sort=occurredAt,asc&limit=1&offset=1").json();
        assertEquals(1, second.get("items").size());
        assertEquals(predictionEvents, second.get("total").asLong());
        assertEquals(oldestFirst.get(1).get("id").asLong(), second.get("items").get(0).get("id").asLong());

        // 3. Comment the anomaly.
        Response created = rest.post("/anomalies/" + anomalyId + "/comments", Map.of("text", "window open"));
        assertEquals(201, created.status(), created.body());
        long commentId = created.json().get("id").asLong();
        assertEquals("/api/v1/comments/" + commentId, created.header("Location").orElseThrow());
        assertEquals("window open", rest.get("/anomalies/" + anomalyId).json().get("comments").get(0).get("text").asText());
        assertEquals(1, total("/anomalies?commented=true"));

        // 4. Edit and delete the comment.
        Response edited = rest.put("/comments/" + commentId, Map.of("text", "window was open"));
        assertEquals(200, edited.status(), edited.body());
        assertEquals("window was open", edited.json().get("text").asText());
        assertFalse(edited.json().get("updatedAt").isNull());
        assertEquals(204, rest.delete("/comments/" + commentId).status());
        assertEquals(0, rest.get("/anomalies/" + anomalyId + "/comments").json().size());

        // 5. Delete the anomaly.
        assertEquals(204, rest.delete("/anomalies/" + anomalyId).status());
        assertEquals(404, rest.get("/anomalies/" + anomalyId).status());
        assertEquals(404, rest.post("/anomalies/" + anomalyId + "/comments", Map.of("text", "late")).status());

        // 6. Delete the CO2 predictions by filter; temperature ones stay.
        long temperaturePredictions = total("/predictions?dataType=temperature");
        Response deleted = rest.delete("/predictions?dataType=co2");
        assertEquals(200, deleted.status(), deleted.body());
        assertEquals(co2Predictions, deleted.json().get("deleted").asLong());
        assertEquals(0, total("/predictions?dataType=co2"));
        assertEquals(temperaturePredictions, total("/predictions?dataType=temperature"));
    }

    @Test
    void invalidRequestsAreReportedAsProblemDetails() {
        assertProblem(rest.delete("/anomalies"), 400, "at least one filter is required to delete several results");
        assertProblem(rest.get("/anomalies?limit=0"), 400, "limit must be between 1 and 500");
        assertProblem(rest.get("/anomalies?from=yesterday"), 400,
                "from must be an ISO-8601 instant such as 2021-11-20T14:00:00Z");
        assertProblem(rest.get("/predictions?chainId=x"), 400, "unknown parameter chainId (only anomalies have a chain)");
        assertProblem(rest.put("/comments/1", Map.of("text", "")), 400, "text must not be empty");
        assertProblem(rest.put("/comments/1", "{not json"), 400, "body must be a JSON object with a text field");
        assertProblem(rest.send("PUT", "/comments/1", "text", "text/plain"), 415, "Content-Type must be application/json");
    }

    @Test
    void liveEndpointsMatchTheRmiApi() throws Exception {
        system.produceTemperatureDrop();
        waiting().until(() -> system.rmi().getStatus().queueSize() == 0);

        List<PredictionDto> rmiPredictions = system.rmi().getCurrentPredictions();
        JsonNode current = rest.get("/predictions/current").json();
        assertEquals(rmiPredictions.size(), current.size());
        for (PredictionDto prediction : rmiPredictions) {
            JsonNode match = find(current, "dataType", prediction.dataType());
            assertEquals(prediction.binLower(), match.get("binLower").asDouble());
            assertEquals(prediction.binUpper(), match.get("binUpper").asDouble());
        }

        ServiceStatusDto rmiStatus = system.rmi().getStatus();
        JsonNode status = rest.get("/status").json();
        assertEquals(rmiStatus.serviceId(), status.get("serviceId").asText());
        assertEquals(rmiStatus.serverEpoch(), status.get("serverEpoch").asLong());
        assertEquals(rmiStatus.dataTypes().size(), status.get("dataTypes").size());
        assertEquals(rmiStatus.fogStatus().keySet().size(), status.get("fogStatus").size());
        assertTrue(status.get("storage").has("storedAnomalies"));
        assertTrue(status.get("storage").has("recorded"));

        JsonNode meta = rest.get("/meta").json();
        JsonNode chain = find(meta.get("chains"), "id", MlTestSystem.CHAIN);
        assertEquals("warm stays warm", chain.get("description").asText());
        assertEquals("temperature", chain.get("dataTypes").get(0).asText());
        assertTrue(meta.get("dataTypes").toString().contains("co2"));
    }

    @Test
    void onlyTheWebUiOriginMayCallTheApiFromABrowser() {
        Response allowed = rest.options("/anomalies", "Origin", WEB_ORIGIN, "Access-Control-Request-Method", "GET");
        Response foreign = rest.options("/anomalies", "Origin", "http://evil.example", "Access-Control-Request-Method", "GET");

        assertEquals(WEB_ORIGIN, allowed.header("Access-Control-Allow-Origin").orElse("-"));
        assertEquals("-", foreign.header("Access-Control-Allow-Origin").orElse("-"));
    }

    private long total(String path) {
        Response response = rest.get(path);
        assertEquals(200, response.status(), path + ": " + response.body());
        return response.json().get("total").asLong();
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (node.get(field).asText().equals(value)) {
                return node;
            }
        }
        throw new AssertionError("no " + field + "=" + value + " in " + array);
    }

    private static void assertProblem(Response response, int status, String detail) {
        assertEquals(status, response.status(), response.body());
        assertTrue(response.contentType().startsWith("application/problem+json"), response.contentType());
        assertEquals(detail, response.json().get("detail").asText());
    }
}
