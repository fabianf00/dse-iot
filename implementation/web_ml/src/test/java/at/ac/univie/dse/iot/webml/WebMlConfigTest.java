package at.ac.univie.dse.iot.webml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebMlConfigTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void theShippedConfigIsValid() {
        WebMlConfig config = loader.load(Path.of("..", "config", "web-ml.json"), WebMlConfig.class);

        assertEquals("web-ml", config.serviceId());
        assertEquals(50400, config.port());
        assertEquals(List.of(new WebMlConfig.Endpoint("ml-1", "http://127.0.0.1:50303/api/v1")), config.endpoints());
        assertEquals(5000, config.refreshIntervalMs());
    }

    @Test
    void optionalValuesHaveDefaults() {
        WebMlConfig config = loader.parse("""
                { "serviceId": "web", "port": 50400,
                  "endpoints": [ { "name": "ml-1", "restUrl": "http://localhost:50303/api/v1/" } ] }
                """, WebMlConfig.class, "web.json");

        assertEquals(WebMlConfig.DEFAULT_BIND_HOST, config.bindHost());
        assertEquals(WebMlConfig.DEFAULT_REFRESH_INTERVAL_MS, config.refreshIntervalMs());
        assertEquals("http://localhost:50303/api/v1", config.endpoints().get(0).restUrl(), "trailing slash removed");
        assertEquals("http://localhost:50303", config.endpoints().get(0).origin());
    }

    @Test
    void invalidValuesAreReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse("""
                { "serviceId": "", "port": 70000, "refreshIntervalMs": 10,
                  "endpoints": [ { "name": "ml-1", "restUrl": "ftp://host/api" },
                                 { "name": "ml-1", "restUrl": "" } ] }
                """, WebMlConfig.class, "web.json"));

        assertEquals(List.of(
                "serviceId must not be blank",
                "port must be between 1 and 65535",
                "endpoints[0].restUrl must be an absolute http:// or https:// URL",
                "endpoints[1].restUrl must not be blank",
                "refreshIntervalMs must be between 1000 and 3600000",
                "endpoints must have unique names"), e.problems());
    }

    @Test
    void endpointsAreRequired() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(
                "{ \"serviceId\": \"web\", \"port\": 50400 }", WebMlConfig.class, "web.json"));

        assertEquals(List.of("endpoints must not be empty"), e.problems());
    }
}
