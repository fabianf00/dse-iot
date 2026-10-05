package at.ac.univie.dse.iot.webml;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.msml.MlTestSystem;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.RestTestClient;
import at.ac.univie.dse.iot.testsupport.RestTestClient.Response;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IT-WEB: WEB ML serves the UI and the endpoints of a running ML; the UI follows the "minimal UI" rules. */
class WebMlIT {

    private static final Path UI_SOURCES = Path.of("..", "web_ml", "src", "main", "resources", "web");
    private static final String WEB_ORIGIN = "http://127.0.0.1:50400";

    private MlTestSystem ml;
    private WebMlService web;
    private RestTestClient browser;
    private String restUrl;

    @BeforeEach
    void setUp() throws Exception {
        ml = MlTestSystem.start(MlTestSystem.memoryDatabase());
        restUrl = "http://127.0.0.1:" + ml.restPort() + "/api/v1";
        web = new WebMlService();
        web.start(new WebMlConfig("web-test", "127.0.0.1", Ports.freeTcpPort(),
                List.of(new WebMlConfig.Endpoint("ml-test", restUrl)), 2000));
        browser = new RestTestClient("http://127.0.0.1:" + web.port());
    }

    @AfterEach
    void tearDown() {
        web.stop();
        ml.close();
    }

    @Test
    void servesTheUiAndItsFiles() {
        Response index = browser.get("/");
        assertEquals(200, index.status());
        assertEquals("text/html; charset=utf-8", index.contentType());
        assertTrue(index.body().contains("<script type=\"module\" src=\"app.js\"></script>"));

        for (Map.Entry<String, String> file : Map.of(
                "/app.js", "text/javascript; charset=utf-8",
                "/style.css", "text/css; charset=utf-8",
                "/vendor/chart.umd.min.js", "text/javascript; charset=utf-8").entrySet()) {
            Response response = browser.get(file.getKey());
            assertEquals(200, response.status(), file.getKey());
            assertEquals(file.getValue(), response.contentType(), file.getKey());
        }
        assertTrue(browser.get("/vendor/chart.umd.min.js").body().contains("Chart.js v4.5.1"));
    }

    @Test
    void configJsonPointsTheBrowserToTheMlRestApi() {
        JsonNode config = browser.get("/config.json").json();

        assertEquals("ml-test", config.get("endpoints").get(0).get("name").asText());
        assertEquals(restUrl, config.get("endpoints").get(0).get("restUrl").asText());
        assertEquals(2000, config.get("refreshIntervalMs").asLong());
        // The browser can actually reach that URL.
        assertEquals(200, new RestTestClient(restUrl).get("/meta").status());
    }

    @Test
    void refusesPathsOutsideTheUiAndOtherMethods() {
        assertEquals(404, browser.get("/../pom.xml").status());
        assertEquals(404, browser.get("/%2e%2e/config.json").status());
        assertEquals(405, browser.post("/", "{}").status());
    }

    @Test
    void htmlInCommentsIsStoredAndReturnedVerbatim() {
        String script = "<script>alert(1)</script>";
        ml.round(21.0, 600.0);
        ml.round(21.2, 600.0);
        waiting().until(() -> ml.rest().get("/predictions").json().get("total").asInt() > 0);
        long id = ml.rest().get("/predictions").json().get("items").get(0).get("id").asLong();

        // What the browser does: preflight from the UI origin, then the POST.
        Response preflight = ml.rest().options("/predictions/" + id + "/comments", "Origin", WEB_ORIGIN,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type");
        assertEquals(WEB_ORIGIN, preflight.header("Access-Control-Allow-Origin").orElse("-"));
        assertTrue(preflight.header("Access-Control-Allow-Headers").orElse("").toLowerCase().contains("content-type"),
                preflight.headers().map().toString());
        Response created = ml.rest().post("/predictions/" + id + "/comments", Map.of("text", script, "author", script));
        assertEquals(201, created.status(), created.body());

        JsonNode comment = ml.rest().get("/predictions/" + id).json().get("comments").get(0);
        assertEquals(script, comment.get("text").asText());
        assertEquals(script, comment.get("author").asText());
    }

    /** Guards the rules of planning/10 §2 and §5: no browser storage, no HTML from data. */
    @Test
    void uiSourcesFollowTheMinimalUiRules() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(UI_SOURCES)) {
            sources = files.filter(Files::isRegularFile)
                    .filter(path -> !UI_SOURCES.relativize(path).startsWith("vendor"))
                    .filter(path -> path.toString().matches(".*\\.(html|js|css)$"))
                    .toList();
        }
        assertEquals(List.of("app.js", "index.html", "style.css"),
                sources.stream().map(path -> path.getFileName().toString()).sorted().toList());
        Pattern forbidden = Pattern.compile(
                "localStorage|sessionStorage|indexedDB|document\\.cookie|innerHTML|outerHTML|insertAdjacentHTML"
                        + "|document\\.write|\\beval\\(|new Function\\(");
        for (Path source : sources) {
            String text = Files.readString(source);
            var match = forbidden.matcher(text);
            assertFalse(match.find(), () -> source + " uses " + match.group());
        }
        String index = Files.readString(UI_SOURCES.resolve("index.html"));
        assertFalse(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(index).find(), "no inline scripts (CSP)");
        assertFalse(Pattern.compile("\\son[a-z]+\\s*=").matcher(index).find(), "no inline event handlers (CSP)");
        assertFalse(index.contains("style="), "no inline styles (CSP)");
    }
}
