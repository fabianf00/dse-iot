package at.ac.univie.dse.iot.clntml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.clntml.ClientConfig.EndpointConfig;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.rmi.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EndpointPollerTest {

    private static final EndpointConfig ENDPOINT = new EndpointConfig("ml-1", "127.0.0.1", 1099, "MlService");

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicReference<MlRemoteService> server = new AtomicReference<>();
    private final ConsoleRenderer renderer = new ConsoleRenderer(new PrintStream(bytes, true, StandardCharsets.UTF_8),
            ZoneOffset.UTC, Clock.fixed(Instant.parse("2021-11-20T14:05:00Z"), ZoneOffset.UTC));
    private final MlEndpointConnection connection = new MlEndpointConnection(ENDPOINT, endpoint -> {
        MlRemoteService service = server.get();
        if (service == null) {
            throw new ConnectException("Connection refused");
        }
        return service;
    }, now::get, 4_000);
    private final PollCursor cursor = new PollCursor();
    private final EndpointPoller poller = new EndpointPoller(connection, cursor, renderer, 2);

    /** Output since the last call, line by line. */
    private List<String> output() {
        String text = bytes.toString(StandardCharsets.UTF_8);
        bytes.reset();
        return text.lines().toList();
    }

    @Test
    void firstPollPrintsAllEventsAcrossPages() {
        server.set(new FakeMlService(7).withPrediction("co2", 680, 720, "PPM").withPrediction("temperature", 21, 22, "CELSIUS")
                .withAnomaly("temp-sudden-drop", 14.2));

        assertTrue(poller.pollOnce());

        List<String> lines = output();
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("INFO        connected to rmi://127.0.0.1:1099/MlService"));
        assertTrue(lines.get(1).contains("PREDICTION  co2"));
        assertTrue(lines.get(2).contains("PREDICTION  temperature"));
        assertTrue(lines.get(3).contains("ANOMALY     temp-sudden-drop"), "third event from the second page");
        assertEquals(3, cursor.position());
    }

    @Test
    void secondPollPrintsOnlyNewEvents() {
        FakeMlService service = new FakeMlService(7).withPrediction("co2", 680, 720, "PPM");
        server.set(service);
        poller.pollOnce();
        output();

        assertTrue(poller.pollOnce());
        assertEquals(List.of("[14:05:00] ml-1  -- no new anomalies or prediction changes --"), output());

        service.withAnomaly("temp-sudden-drop", 14.2);
        poller.pollOnce();
        List<String> lines = output();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("ANOMALY"));
    }

    @Test
    void restartedServerResetsTheCursor() {
        FakeMlService before = new FakeMlService(7).withPrediction("co2", 680, 720, "PPM").withPrediction("co2", 720, 760, "PPM");
        server.set(before);
        poller.pollOnce();
        output();

        before.shutDown();
        server.set(new FakeMlService(8).withPrediction("temperature", 21, 22, "CELSIUS"));
        assertFalse(poller.pollOnce(), "the old stub fails");
        now.addAndGet(1_000);
        assertTrue(poller.pollOnce(), "a new stub is looked up");

        List<String> lines = output();
        assertTrue(lines.get(0).contains("WARN        unreachable (no such object in table)"), lines.toString());
        assertTrue(lines.get(1).contains("INFO        server restarted (new epoch), cursor reset"), lines.toString());
        assertTrue(lines.get(2).contains("PREDICTION  temperature"), "event 1 of the new instance is not skipped");
        assertTrue(lines.get(3).contains("INFO        reachable again"), lines.toString());
        assertEquals(new PollCursor.Snapshot(8, 1), cursor.snapshot());
    }

    @Test
    void savedCursorOfAnotherEpochIsReset() {
        PollCursor saved = new PollCursor(new PollCursor.Snapshot(5, 40));
        EndpointPoller resumed = new EndpointPoller(connection, saved, renderer, 10);
        server.set(new FakeMlService(7).withPrediction("co2", 680, 720, "PPM"));

        resumed.pollOnce();

        assertTrue(output().stream().anyMatch(l -> l.contains("PREDICTION  co2")));
        assertEquals(new PollCursor.Snapshot(7, 1), saved.snapshot());
    }

    @Test
    void clientThatFellBehindIsWarned() {
        FakeMlService service = new FakeMlService(7);
        for (int i = 0; i < 5; i++) {
            service.withPrediction("co2", i * 50, i * 50 + 50, "PPM");
        }
        service.discardUpTo(3);
        server.set(service);

        poller.pollOnce();

        List<String> lines = output();
        assertTrue(lines.get(1).contains("WARN        fell behind: events 1 to 3 were already discarded by the server"),
                lines.toString());
        assertEquals(5, cursor.position());
    }

    @Test
    void unreachableEndpointIsRetriedWithBackoff() {
        assertFalse(poller.pollOnce());
        assertEquals(List.of("[14:05:00] ml-1  WARN        unreachable (Connection refused), retry in 1 s"), output());

        assertFalse(poller.pollOnce(), "still waiting");
        assertTrue(output().isEmpty(), "no output while waiting");

        now.addAndGet(1_000);
        assertFalse(poller.pollOnce());
        assertTrue(output().get(0).endsWith("retry in 2 s"));

        now.addAndGet(2_000);
        assertFalse(poller.pollOnce());
        now.addAndGet(4_000);
        assertFalse(poller.pollOnce());
        assertTrue(output().get(1).endsWith("retry in 4 s"), "backoff is capped");

        server.set(new FakeMlService(7));
        now.addAndGet(4_000);
        assertTrue(poller.pollOnce());
        List<String> lines = output();
        assertTrue(lines.stream().anyMatch(l -> l.contains("reachable again")), lines.toString());
    }

    @Test
    void failedCallDropsTheCachedStub() {
        server.set(new FakeMlService(7));
        assertTrue(poller.pollOnce());
        server.set(null);

        assertEquals(1_000, connection.failed());
        now.addAndGet(1_000);

        assertFalse(poller.pollOnce(), "the stub was dropped, so the next poll looks it up again and fails");
    }
}
