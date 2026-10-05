package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.clntml.ClientConfig.EndpointConfig;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The RMI stub of one ML instance. The stub is looked up lazily and kept. After a failure it is
 * dropped and the next attempt waits with exponential backoff (1 s, 2 s, 4 s … up to the
 * configured maximum), so a dead endpoint costs nothing between attempts.
 */
public final class MlEndpointConnection {

    private static final long INITIAL_BACKOFF_MS = 1_000;

    /** Finds the remote service of an endpoint; the default asks the endpoint's RMI registry. */
    @FunctionalInterface
    public interface StubLocator {
        MlRemoteService locate(EndpointConfig endpoint) throws RemoteException, NotBoundException;

        StubLocator RMI_REGISTRY = endpoint -> (MlRemoteService) LocateRegistry
                .getRegistry(endpoint.host(), endpoint.registryPort())
                .lookup(endpoint.bindName());
    }

    private final EndpointConfig endpoint;
    private final StubLocator locator;
    private final LongSupplier clock;
    private final long maxBackoffMs;
    private MlRemoteService stub;
    private long backoffMs;
    private long nextAttemptAt;

    public MlEndpointConnection(EndpointConfig endpoint, StubLocator locator, LongSupplier clock, long maxBackoffMs) {
        this.endpoint = endpoint;
        this.locator = locator;
        this.clock = clock;
        this.maxBackoffMs = maxBackoffMs;
    }

    /**
     * The stub, looked up if needed; empty while waiting for the next attempt after a failure.
     *
     * @throws RemoteException if the lookup fails; call {@link #failed()} afterwards
     */
    public synchronized Optional<MlRemoteService> service() throws RemoteException {
        if (clock.getAsLong() < nextAttemptAt) {
            return Optional.empty();
        }
        if (stub == null) {
            try {
                stub = locator.locate(endpoint);
            } catch (NotBoundException e) {
                throw new RemoteException("nothing bound as '" + endpoint.bindName() + "'", e);
            }
        }
        return Optional.of(stub);
    }

    /** Drops the stub and schedules the next attempt; returns the wait in milliseconds. */
    public synchronized long failed() {
        stub = null;
        backoffMs = backoffMs == 0 ? INITIAL_BACKOFF_MS : Math.min(backoffMs * 2, maxBackoffMs);
        nextAttemptAt = clock.getAsLong() + backoffMs;
        return backoffMs;
    }

    /** Records a successful call; returns {@code true} if the endpoint had failed before. */
    public synchronized boolean succeeded() {
        boolean recovered = backoffMs > 0;
        backoffMs = 0;
        nextAttemptAt = 0;
        return recovered;
    }

    public EndpointConfig endpoint() {
        return endpoint;
    }
}
