package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.clntml.ClientConfig.EndpointConfig;
import at.ac.univie.dse.iot.clntml.MlEndpointConnection.StubLocator;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLNT ML: a standalone command line client that polls one or more ML instances over Java RMI
 * and prints new anomalies and prediction changes to the console.
 *
 * <p>Three modes: continuous polling ({@link #run(at.ac.univie.dse.iot.common.config.ServiceConfig)},
 * stopped with Ctrl+C), a single poll ({@link #runOnce}) and a status query ({@link #runStatus}).
 */
public final class MlClient extends AbstractMicroService<ClientConfig> {

    private final PrintStream out;
    private final ZoneId zone;
    private final Clock clock;
    private final StubLocator locator;
    private final CursorStore cursorStore;
    private ConsoleRenderer renderer;
    private PollingScheduler scheduler;

    /**
     * @param cursorFile where cursors are kept between runs, or {@code null} to start from the
     *                   beginning of each server's event log
     */
    public MlClient(PrintStream out, Path cursorFile) {
        this(out, ZoneId.systemDefault(), Clock.systemUTC(), StubLocator.RMI_REGISTRY, cursorFile);
    }

    MlClient(PrintStream out, ZoneId zone, Clock clock, StubLocator locator, Path cursorFile) {
        super(ClientConfig.class);
        this.out = out;
        this.zone = zone;
        this.clock = clock;
        this.locator = locator;
        this.cursorStore = cursorFile == null ? null : new CursorStore(cursorFile);
    }

    /**
     * Polls every endpoint once, prints the result and saves the cursors.
     *
     * @return {@link #EXIT_OK} if every endpoint answered, {@link #EXIT_FAILURE} otherwise
     */
    public int runOnce(ClientConfig config) {
        initialize(config);
        boolean allAnswered = scheduler.pollAllOnce();
        saveCursors();
        return allAnswered ? EXIT_OK : EXIT_FAILURE;
    }

    /**
     * Prints the status of every endpoint.
     *
     * @return {@link #EXIT_OK} if every endpoint answered, {@link #EXIT_FAILURE} otherwise
     */
    public int runStatus(ClientConfig config) {
        initialize(config);
        boolean allAnswered = true;
        for (EndpointPoller poller : scheduler.pollers()) {
            allAnswered &= poller.printStatus();
        }
        return allAnswered ? EXIT_OK : EXIT_FAILURE;
    }

    @Override
    protected void initialize(ClientConfig config) {
        renderer = new ConsoleRenderer(out, zone, clock);
        Map<String, PollCursor.Snapshot> saved = cursorStore == null ? Map.of() : cursorStore.load();
        List<EndpointPoller> pollers = new ArrayList<>();
        for (EndpointConfig endpoint : config.endpoints()) {
            MlEndpointConnection connection = new MlEndpointConnection(endpoint, locator, clock::millis,
                    config.reconnectBackoffMaxMs());
            PollCursor cursor = new PollCursor(saved.getOrDefault(endpoint.name(), new PollCursor.Snapshot(0, 0)));
            pollers.add(new EndpointPoller(connection, cursor, renderer, config.maxEventsPerPoll()));
        }
        scheduler = new PollingScheduler(pollers);
    }

    @Override
    protected void startComponents() {
        out.printf("Polling %d ML instance(s) every %d ms, Ctrl+C to stop%n", config().endpoints().size(),
                config().pollIntervalMs());
        scheduler.start(Duration.ofMillis(config().pollIntervalMs()),
                new NamedThreadFactory(config().serviceId() + "-poller", true));
    }

    @Override
    protected void stopComponents() {
        if (scheduler != null) {
            scheduler.stop();
            saveCursors();
        }
    }

    private void saveCursors() {
        if (cursorStore == null) {
            return;
        }
        Map<String, PollCursor.Snapshot> cursors = new LinkedHashMap<>();
        scheduler.pollers().forEach(poller -> cursors.put(poller.name(), poller.cursor().snapshot()));
        try {
            cursorStore.save(cursors);
        } catch (IOException e) {
            log.warn("Could not save cursors: {}", e.getMessage());
        }
    }
}
