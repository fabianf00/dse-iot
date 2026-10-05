package at.ac.univie.dse.iot.clntml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.PhaseObservationDto;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.PredictionDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Arguments, config, formatting and the cursor file. */
class ClientTest {

    private static final Instant AT = Instant.parse("2021-11-20T14:03:05Z");

    @Test
    void argumentsAreParsed() {
        ClientArguments arguments = ClientArguments.parse(new String[] {
                "config/clnt-ml.json", "--interval", "2000", "--once", "--cursor-file", "cursors.json"});

        assertEquals(Path.of("config/clnt-ml.json"), arguments.configFile());
        assertEquals(2000L, arguments.pollIntervalMs());
        assertTrue(arguments.once());
        assertEquals(Path.of("cursors.json"), arguments.cursorFile());

        ClientArguments minimal = ClientArguments.parse(new String[] {"c.json"});
        assertNull(minimal.pollIntervalMs());
        assertFalse(minimal.once());
        assertFalse(minimal.status());
        assertTrue(ClientArguments.parse(new String[] {"c.json", "--status"}).status());
        assertNull(minimal.cursorFile());
    }

    @Test
    void invalidArgumentsAreExplained() {
        assertEquals("missing config file", parseError());
        assertEquals("unknown option --fast", parseError("c.json", "--fast"));
        assertEquals("--interval needs a value", parseError("c.json", "--interval"));
        assertEquals("--interval must be > 0", parseError("c.json", "--interval", "0"));
        assertEquals("--interval must be a number of milliseconds, not 'soon'", parseError("c.json", "--interval", "soon"));
        assertEquals("only one config file may be given", parseError("a.json", "b.json"));
        assertEquals("--once and --status cannot be combined", parseError("c.json", "--once", "--status"));
    }

    private static String parseError(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> ClientArguments.parse(args)).getMessage();
    }

    @Test
    void shippedConfigIsValidAndUsesDefaults() {
        ClientConfig config = new ConfigLoader().load(Path.of("..", "config", "clnt-ml.json"), ClientConfig.class);

        assertEquals("clnt-ml", config.serviceId());
        assertEquals(5_000, config.pollIntervalMs());
        assertEquals(3_000, config.connectTimeoutMs());
        assertEquals("rmi://127.0.0.1:1099/MlService", config.endpoints().get(0).url());
        assertEquals(2_000, config.withPollInterval(2_000).pollIntervalMs());
    }

    @Test
    void invalidConfigIsReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> new ConfigLoader().parse("""
                { "maxEventsPerPoll": 1000, "endpoints": [ { "name": "a", "host": "h" }, { "name": "a", "host": "" } ] }
                """, ClientConfig.class, "clnt.json"));

        assertEquals(List.of("maxEventsPerPoll must be between 1 and 500", "endpoints[1].host must not be blank",
                "endpoints must have unique names"), e.problems());
    }

    @Test
    void anomalyLineShowsChainExpectationAndObservation() {
        PhaseObservationDto phase = new PhaseObservationDto("temperature > 20.0", AT, AT, 6);
        AnomalyDto anomaly = new AnomalyDto(16, AT, "temperature", "temp-sudden-drop", "d", phase, phase,
                "temperature > 20.0", 14.2, "CELSIUS", AT);

        assertEquals("temp-sudden-drop   expected temperature > 20.0 °C, observed 14.2 °C",
                new AnomalyFormatter().format(anomaly));
    }

    @Test
    void predictionLineShowsRangesConfidenceAndSamples() {
        PredictionChangeDto change = new PredictionChangeDto(17, AT, "co2",
                new PredictionDto("co2", 640, 680, 660, 0.31, 480, "PPM"),
                new PredictionDto("co2", 680, 720, 700, 0.3449, 480, "PPM"));
        PredictionChangeDto first = new PredictionChangeDto(1, AT, "temperature", null,
                new PredictionDto("temperature", 22.96, 23.37, 23.165, 1, 1, "CELSIUS"));

        assertEquals("co2                680–720 ppm (was 640–680 ppm), confidence 34 %, n=480",
                new PredictionChangeFormatter().format(change));
        assertEquals("temperature        22.96–23.37 °C (first prediction), confidence 100 %, n=1",
                new PredictionChangeFormatter().format(first));
    }

    @Test
    void unknownUnitsArePrintedAsTheyAre() {
        assertEquals("3 lux", EventFormatter.value(3.0, "lux"));
        assertEquals("1.5", EventFormatter.value(1.4999, null));
    }

    @Test
    void cursorFileRoundTrip(@TempDir Path dir) throws IOException {
        CursorStore store = new CursorStore(dir.resolve("cursors.json"));

        assertEquals(Map.of(), store.load(), "missing file means no cursors");
        store.save(Map.of("ml-1", new PollCursor.Snapshot(7, 17)));

        assertEquals(Map.of("ml-1", new PollCursor.Snapshot(7, 17)), store.load());
    }

    @Test
    void unreadableCursorFileIsReported(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("cursors.json"), "not json");

        assertThrows(IllegalStateException.class, () -> new CursorStore(file).load());
    }

    @Test
    void cursorAdoptsEpochs() {
        PollCursor cursor = new PollCursor();

        assertFalse(cursor.adoptEpoch(7), "first epoch is not a restart");
        cursor.advanceTo(5);
        assertFalse(cursor.adoptEpoch(7));
        assertEquals(5, cursor.position());
        assertTrue(cursor.adoptEpoch(8));
        assertEquals(0, cursor.position());
    }
}
