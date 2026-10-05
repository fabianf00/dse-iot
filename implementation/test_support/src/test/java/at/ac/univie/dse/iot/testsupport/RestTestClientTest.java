package at.ac.univie.dse.iot.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.testsupport.RestTestClient.Response;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestTestClientTest {

    private HttpServer server;
    private RestTestClient client;

    /** Answers every request with a JSON echo of what it received. */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(Ports.HOST, 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] echo = JsonCodec.shared().toBytes(Map.of(
                    "method", exchange.getRequestMethod(),
                    "uri", exchange.getRequestURI().toString(),
                    "contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                    "custom", String.valueOf(exchange.getRequestHeaders().getFirst("X-Test")),
                    "body", body));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/api/v1/things/7");
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 201 : 200, echo.length);
            exchange.getResponseBody().write(echo);
            exchange.close();
        });
        server.start();
        client = new RestTestClient("http://" + Ports.HOST + ":" + server.getAddress().getPort() + "/api/v1");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void getSendsPathQueryAndHeaders() {
        Response response = client.get("/things?limit=2", "X-Test", "yes");

        assertEquals(200, response.status());
        assertEquals("application/json", response.contentType());
        assertEquals("GET", response.json().get("method").asText());
        assertEquals("/api/v1/things?limit=2", response.json().get("uri").asText());
        assertEquals("yes", response.json().get("custom").asText());
    }

    @Test
    void postSendsObjectsAsJson() {
        Response response = client.post("/things", Map.of("text", "hi"));

        assertEquals(201, response.status());
        assertEquals("/api/v1/things/7", response.header("Location").orElseThrow());
        assertEquals("application/json", response.json().get("contentType").asText());
        assertEquals("{\"text\":\"hi\"}", response.json().get("body").asText());
    }

    @Test
    void stringBodiesAreSentAsTheyAre() {
        Response response = client.send("PUT", "/things/7", "{not json", "text/plain");

        assertEquals("PUT", response.json().get("method").asText());
        assertEquals("text/plain", response.json().get("contentType").asText());
        assertEquals("{not json", response.json().get("body").asText());
        assertEquals("{not json", client.put("/things/7", "{not json").json().get("body").asText());
    }
}
