package at.ac.univie.dse.iot.mlapi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * ML Service API v1: synchronous Java RMI API of MS ML for visualization clients.
 *
 * <p>Endpoint: {@code rmi://<host>:<registryPort>/<bindName>}, by default
 * {@code rmi://127.0.0.1:1099/MlService}.
 *
 * <p>Clients poll {@link #pollEvents} with the cursor of their previous poll to get the anomalies
 * and prediction changes that happened since. A changed {@link PollResult#serverEpoch()} means the
 * service restarted and its sequence numbers start again; the client then resets its cursor to 0.
 */
public interface MlRemoteService extends Remote {

    /** Name the service is bound under in the RMI registry, unless configured otherwise. */
    String DEFAULT_BIND_NAME = "MlService";

    /** Upper limit for {@code maxEvents}; larger requests are capped. */
    int MAX_EVENTS_PER_POLL = 500;

    /**
     * Events with a sequence number greater than {@code afterSequence}, oldest first.
     *
     * @param afterSequence cursor from the previous poll ({@link PollResult#nextCursor()}), 0 for the first poll
     * @param maxEvents     maximum number of events to return, capped at {@value #MAX_EVENTS_PER_POLL}
     * @throws IllegalArgumentException if {@code afterSequence < 0} or {@code maxEvents <= 0}
     */
    PollResult pollEvents(long afterSequence, int maxEvents) throws RemoteException;

    /** Current prediction for every data type that has one (a snapshot, not incremental). */
    List<PredictionDto> getCurrentPredictions() throws RemoteException;

    /** Health and meta information. */
    ServiceStatusDto getStatus() throws RemoteException;
}
