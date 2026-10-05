package at.ac.univie.dse.iot.webml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.RestTestClient;
import at.ac.univie.dse.iot.testsupport.RestTestClient.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebMlServiceTest {

    private WebMlService service;
    private RestTestClient http;

    @BeforeEach
    void start() {
        service = new WebMlService();
        service.start(new WebMlConfig("web-test", "127.0.0.1", Ports.freeTcpPort(), List.of(
                new WebMlConfig.Endpoint("ml-1", "http://127.0.0.1:50303/api/v1"),
                new WebMlConfig.Endpoint("ml-2", "http://10.0.0.2:50303/api/v1")), 2000));
        http = new RestTestClient("http://127.0.0.1:" + service.port());
    }

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void configJsonListsTheEndpointsForTheBrowser() {
        Response response = http.get("/config.json");

        assertEquals(200, response.status());
        assertEquals("application/json", response.contentType());
        JsonNode config = response.json();
        assertEquals(2000, config.get("refreshIntervalMs").asLong());
        assertEquals("ml-2", config.get("endpoints").get(1).get("name").asText());
        assertEquals("http://10.0.0.2:50303/api/v1", config.get("endpoints").get(1).get("restUrl").asText());
    }

    @Test
    void servesTheUiWithSecurityHeaders() {
        Response response = http.get("/");

        assertEquals(200, response.status());
        assertEquals("text/html; charset=utf-8", response.contentType());
        assertTrue(response.body().contains("app.js"));
        assertEquals("nosniff", response.header("X-Content-Type-Options").orElseThrow());
        String csp = response.header("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("script-src 'self'"), csp);
        assertTrue(csp.contains("connect-src 'self' http://127.0.0.1:50303 http://10.0.0.2:50303"), csp);
    }

    @Test
    void headSendsHeadersOnly() {
        Response response = http.send("HEAD", "/app.js", null, null);

        assertEquals(200, response.status());
        assertEquals("text/javascript; charset=utf-8", response.contentType());
        assertEquals("", response.body());
    }

    @Test
    void unknownPathsAndOtherMethodsAreRefused() {
        assertEquals(404, http.get("/nothing.js").status());
        assertEquals(404, http.get("/../pom.xml").status());
        assertEquals(404, http.get("/%2e%2e/config.json").status());
        assertEquals(404, http.get("/vendor/%2e%2e/app.js").status());
        Response post = http.post("/", "{}");
        assertEquals(405, post.status());
        assertEquals("GET, HEAD", post.header("Allow").orElseThrow());
        assertEquals(405, http.send("DELETE", "/config.json", null, null).status());
    }
}
