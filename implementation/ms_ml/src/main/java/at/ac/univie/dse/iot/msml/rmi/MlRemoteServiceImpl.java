package at.ac.univie.dse.iot.msml.rmi;

import at.ac.univie.dse.iot.msml.events.EventLog;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.mlapi.PollResult;
import at.ac.univie.dse.iot.mlapi.PredictionDto;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import java.util.List;
import java.util.function.Supplier;

/**
 * Implementation of the ML Service API (Remote Facade): reads the event log and the current
 * predictions and maps them to DTOs. Called concurrently by RMI threads; all state it reads is
 * thread-safe.
 */
public final class MlRemoteServiceImpl implements MlRemoteService {

    private final EventLog events;
    private final HistogramPredictor predictor;
    private final Supplier<ServiceStatusDto> status;

    /**
     * @param status builds the current status; called for every {@link #getStatus()}
     */
    public MlRemoteServiceImpl(EventLog events, HistogramPredictor predictor, Supplier<ServiceStatusDto> status) {
        this.events = events;
        this.predictor = predictor;
        this.status = status;
    }

    @Override
    public PollResult pollEvents(long afterSequence, int maxEvents) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be >= 0 but was " + afterSequence);
        }
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be > 0 but was " + maxEvents);
        }
        EventLog.Page page = events.since(afterSequence, Math.min(maxEvents, MAX_EVENTS_PER_POLL));
        return new PollResult(events.epoch(), page.events().stream().map(DtoMapper::toDto).toList(),
                page.nextCursor(), page.truncated(), page.hasMore());
    }

    @Override
    public List<PredictionDto> getCurrentPredictions() {
        return predictor.currentAll().stream().map(DtoMapper::toDto).toList();
    }

    @Override
    public ServiceStatusDto getStatus() {
        return status.get();
    }
}
