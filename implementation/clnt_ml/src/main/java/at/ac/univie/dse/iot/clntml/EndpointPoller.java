package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.mlapi.MlEventDto;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.mlapi.PollResult;
import java.rmi.RemoteException;
import java.util.Optional;

/**
 * Polls one ML instance: fetches the events after its cursor and prints them.
 *
 * <ul>
 *   <li>Pages are fetched until the server reports no more ({@code hasMore}), at most
 *       {@value #MAX_PAGES_PER_POLL} per poll.</li>
 *   <li>A changed server epoch means the service restarted: the cursor is reset to 0 and the
 *       events of the new instance are fetched.</li>
 *   <li>If the server already discarded events after the cursor, a warning says so.</li>
 *   <li>Failures are printed once per attempt with the time of the next attempt; the endpoint
 *       is then skipped until that time.</li>
 * </ul>
 */
public final class EndpointPoller {

    static final int MAX_PAGES_PER_POLL = 20;

    private final MlEndpointConnection connection;
    private final PollCursor cursor;
    private final ConsoleRenderer renderer;
    private final int maxEventsPerPoll;
    private final String name;
    private boolean everConnected;

    public EndpointPoller(MlEndpointConnection connection, PollCursor cursor, ConsoleRenderer renderer, int maxEventsPerPoll) {
        this.connection = connection;
        this.cursor = cursor;
        this.renderer = renderer;
        this.maxEventsPerPoll = maxEventsPerPoll;
        this.name = connection.endpoint().name();
    }

    /**
     * Polls once and prints the result.
     *
     * @return {@code true} if the endpoint answered
     */
    public synchronized boolean pollOnce() {
        try {
            Optional<MlRemoteService> service = connection.service();
            if (service.isEmpty()) {
                return false;
            }
            fetchAndPrint(service.get());
            if (connection.succeeded()) {
                renderer.info(name, "reachable again at " + connection.endpoint().url());
            }
            return true;
        } catch (RemoteException e) {
            long retryMs = connection.failed();
            renderer.warn(name, "unreachable (" + reason(e) + "), retry in " + Math.max(1, retryMs / 1000) + " s");
            return false;
        }
    }

    /**
     * Prints the service status of the endpoint (one request, no retry).
     *
     * @return {@code true} if the endpoint answered
     */
    public synchronized boolean printStatus() {
        try {
            Optional<MlRemoteService> service = connection.service();
            if (service.isEmpty()) {
                return false;
            }
            renderer.status(name, connection.endpoint().url(), service.get().getStatus());
            return true;
        } catch (RemoteException e) {
            connection.failed();
            renderer.warn(name, "unreachable (" + reason(e) + ")");
            return false;
        }
    }

    public PollCursor cursor() {
        return cursor;
    }

    public String name() {
        return name;
    }

    private void fetchAndPrint(MlRemoteService service) throws RemoteException {
        long requested = cursor.position();
        PollResult result = service.pollEvents(requested, maxEventsPerPoll);
        if (!everConnected) {
            everConnected = true;
            renderer.info(name, "connected to " + connection.endpoint().url());
        }
        long knownEpoch = cursor.snapshot().serverEpoch();
        if (cursor.adoptEpoch(result.serverEpoch())) {
            renderer.info(name, "server restarted (new epoch), cursor reset");
        }
        if (knownEpoch != result.serverEpoch() && requested != 0) {
            // The answer was relative to a cursor of another server instance.
            result = service.pollEvents(0, maxEventsPerPoll);
        }
        boolean printed = print(result);
        for (int pages = 1; result.hasMore() && pages < MAX_PAGES_PER_POLL; pages++) {
            result = service.pollEvents(cursor.position(), maxEventsPerPoll);
            printed |= print(result);
        }
        if (!printed) {
            renderer.nothingNew(name);
        }
    }

    /** Prints a page and advances the cursor; returns whether it contained events. */
    private boolean print(PollResult result) {
        if (result.truncated() && !result.events().isEmpty()) {
            MlEventDto first = result.events().get(0);
            renderer.warn(name, "fell behind: events " + (cursor.position() + 1) + " to " + (first.sequence() - 1)
                    + " were already discarded by the server");
        }
        renderer.events(name, result.events());
        cursor.advanceTo(result.nextCursor());
        return !result.events().isEmpty();
    }

    private static String reason(RemoteException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
    }
}
