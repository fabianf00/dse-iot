package at.ac.univie.dse.iot.msml.rest;

import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.anomaly;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.anomalyEntity;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.prediction;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.predictionEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msml.prediction.HistogramConfig;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import at.ac.univie.dse.iot.msml.rest.Resources.ChainResource;
import at.ac.univie.dse.iot.msml.rest.Resources.MetaResource;
import at.ac.univie.dse.iot.msml.rest.Resources.StatusResource;
import at.ac.univie.dse.iot.msml.rest.Resources.StorageStatusResource;
import at.ac.univie.dse.iot.msml.results.ResultService;
import at.ac.univie.dse.iot.msml.storage.Database;
import at.ac.univie.dse.iot.msml.storage.JpaResultRepository;
import at.ac.univie.dse.iot.msml.storage.StorageFixtures;
import at.ac.univie.dse.iot.testsupport.RestTestClient;
import at.ac.univie.dse.iot.testsupport.RestTestClient.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP layer: routes, status codes, problem details, CORS. */
class RestApiTest {

    private static final String WEB_ORIGIN = "http://127.0.0.1:50400";

    private final Database database = StorageFixtures.memoryDatabase();
    private final JpaResultRepository repository = new JpaResultRepository(database);
    private final HistogramPredictor predictor = new HistogramPredictor(List.of(
            new HistogramConfig(DataType.of("co2"), 50, 0, 10)));
    private RestServer server;
    private RestTestClient client;

    @BeforeEach
    void start() {
        repository.record(anomalyEntity(anomaly(1, "temperature", "temp-sudden-drop", 10, 14.2)));
        repository.record(anomalyEntity(anomaly(2, "co2", "co2-empty-room", 20, 1400)));
        repository.record(predictionEntity(prediction(3, "co2", 30, 500)));
        predictor.update(DataType.of("co2"), Unit.PPM, 610);
        StatusResource status = new StatusResource("ml-test", 1, Instant.EPOCH, List.of("co2"), Map.of("fog-co2", "ACTIVE"),
                0, 0, 3, new StorageStatusResource(2, 1, 3, 0, 0));
        MetaResource meta = new MetaResource(List.of("co2", "temperature"),
                List.of(new ChainResource("temp-sudden-drop", "warm stays warm", List.of("temperature"))));
        server = new RestServer(new RestConfig("127.0.0.1", 0, List.of(WEB_ORIGIN)),
                new ResultService(repository, Clock.systemUTC()), predictor, () -> status, () -> meta);
        server.start();
        client = new RestTestClient("http://127.0.0.1:" + server.port() + RestServer.BASE_PATH);
    }

    @AfterEach
    void stop() {
        server.stop();
        database.close();
    }

    private long firstAnomalyId() {
        return client.get("/anomalies?sort=occurredAt,asc").json().get("items").get(0).get("id").asLong();
    }

    private static void assertProblem(Response response, int status, String detail) {
        assertEquals(status, response.status(), response.body());
        assertTrue(response.contentType().startsWith("application/problem+json"), response.contentType());
        JsonNode problem = response.json();
        assertEquals(status, problem.get("status").asInt());
        assertEquals("about:blank", problem.get("type").asText());
        assertEquals(detail, problem.get("detail").asText());
    }

    @Test
    void listsStoredResultsWithPaging() {
        Response response = client.get("/anomalies?limit=1&sort=occurredAt,asc");

        assertEquals(200, response.status());
        assertTrue(response.contentType().startsWith("application/json"));
        JsonNode page = response.json();
        assertEquals(2, page.get("total").asInt());
        assertEquals(1, page.get("limit").asInt());
        JsonNode anomaly = page.get("items").get(0);
        assertEquals("ANOMALY", anomaly.get("kind").asText());
        assertEquals("temp-sudden-drop", anomaly.get("chainId").asText());
        assertEquals(14.2, anomaly.get("observedValue").asDouble());
        assertEquals("2021-11-20T14:00:10Z", anomaly.get("occurredAt").asText(), "ISO-8601 instants");
        assertEquals("temperature > 20.0", anomaly.get("past").get("condition").asText());
        assertEquals(1, client.get("/predictions").json().get("total").asInt());
        assertEquals(1, client.get("/anomalies?dataType=co2").json().get("total").asInt());
    }

    @Test
    void getsOneResultOrAnswers404() {
        long id = firstAnomalyId();

        assertEquals(id, client.get("/anomalies/" + id).json().get("id").asLong());
        assertProblem(client.get("/predictions/" + id), 404, "prediction " + id + " does not exist");
        assertProblem(client.get("/anomalies/abc"), 400, "id must be a positive number, not 'abc'");
    }

    @Test
    void invalidFiltersAreReported() {
        assertProblem(client.get("/anomalies?colour=red"), 400, "unknown parameter colour");
        assertProblem(client.get("/predictions?limit=0"), 400, "limit must be between 1 and 500");
    }

    @Test
    void commentsCanBeAddedEditedAndDeleted() {
        long id = firstAnomalyId();

        Response created = client.post("/anomalies/" + id + "/comments", Map.of("text", "window open", "author", "ops"));
        assertEquals(201, created.status(), created.body());
        long commentId = created.json().get("id").asLong();
        assertEquals("/api/v1/comments/" + commentId, created.header("Location").orElseThrow());
        assertEquals("window open", client.get("/anomalies/" + id).json().get("comments").get(0).get("text").asText());
        assertEquals(1, client.get("/anomalies?commented=true").json().get("total").asInt());

        Response edited = client.put("/comments/" + commentId, Map.of("text", "window was open"));
        assertEquals(200, edited.status());
        assertEquals("window was open", edited.json().get("text").asText());
        assertFalse(edited.json().get("updatedAt").isNull());

        assertEquals(204, client.delete("/comments/" + commentId).status());
        assertProblem(client.delete("/comments/" + commentId), 404, "comment " + commentId + " does not exist");
        assertEquals(0, client.get("/anomalies/" + id + "/comments").json().size());
    }

    @Test
    void invalidCommentRequestsAreReported() {
        long id = firstAnomalyId();

        assertProblem(client.post("/anomalies/" + id + "/comments", Map.of("text", " ")), 400, "text must not be empty");
        assertProblem(client.post("/anomalies/" + id + "/comments", "{not json"), 400,
                "body must be a JSON object with a text field");
        assertProblem(client.send("POST", "/anomalies/" + id + "/comments", "text=hi", "text/plain"), 415,
                "Content-Type must be application/json");
        assertProblem(client.post("/predictions/" + id + "/comments", Map.of("text", "x")), 404,
                "prediction " + id + " does not exist");
    }

    @Test
    void resultsCanBeDeletedOneByOneOrByFilter() {
        long id = firstAnomalyId();

        assertEquals(204, client.delete("/anomalies/" + id).status());
        assertProblem(client.get("/anomalies/" + id), 404, "anomaly " + id + " does not exist");
        assertProblem(client.delete("/anomalies"), 400, "at least one filter is required to delete several results");

        Response deleted = client.delete("/anomalies?dataType=co2");
        assertEquals(200, deleted.status());
        assertEquals(1, deleted.json().get("deleted").asInt());
        assertEquals(0, client.get("/anomalies").json().get("total").asInt());
        assertEquals(1, client.get("/predictions").json().get("total").asInt(), "predictions untouched");
    }

    @Test
    void liveEndpointsServeCurrentData() {
        JsonNode current = client.get("/predictions/current").json();
        assertEquals("co2", current.get(0).get("dataType").asText());
        assertEquals(600, current.get(0).get("binLower").asDouble());

        JsonNode status = client.get("/status").json();
        assertEquals("ml-test", status.get("serviceId").asText());
        assertEquals(2, status.get("storage").get("storedAnomalies").asInt());

        JsonNode meta = client.get("/meta").json();
        assertEquals("temp-sudden-drop", meta.get("chains").get(0).get("id").asText());
    }

    @Test
    void unknownEndpointsAndMethodsGetProblemDetails() {
        assertProblem(client.get("/nothing-here"), 404, "no endpoint GET /api/v1/nothing-here");
        assertProblem(client.send("PUT", "/anomalies", "{}", "application/json"), 405,
                "PUT is not allowed on /api/v1/anomalies");
    }

    @Test
    void browsersOnTheWebUiOriginMayCallTheApi() {
        Response allowed = client.options("/anomalies", "Origin", WEB_ORIGIN, "Access-Control-Request-Method", "DELETE");
        Response foreign = client.options("/anomalies", "Origin", "http://evil.example", "Access-Control-Request-Method", "DELETE");
        Response get = client.get("/anomalies", "Origin", WEB_ORIGIN);

        assertEquals(WEB_ORIGIN, allowed.header("Access-Control-Allow-Origin").orElse("-"));
        assertTrue(allowed.header("Access-Control-Allow-Methods").orElse("").contains("DELETE"));
        assertEquals("-", foreign.header("Access-Control-Allow-Origin").orElse("-"));
        assertEquals(WEB_ORIGIN, get.header("Access-Control-Allow-Origin").orElse("-"));
        assertTrue(get.header("Access-Control-Expose-Headers").orElse("").contains("Location"));
    }

    @Test
    void unavailableDatabaseGives503() {
        database.close();

        assertProblem(client.get("/anomalies"), 503, "the result database is not available");
        assertEquals(200, client.get("/predictions/current").status(), "live data does not need the database");
    }
}
