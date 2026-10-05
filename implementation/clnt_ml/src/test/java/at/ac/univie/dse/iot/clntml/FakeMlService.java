package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.MlEventDto;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.mlapi.PhaseObservationDto;
import at.ac.univie.dse.iot.mlapi.PollResult;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.PredictionDto;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** In-memory ML service with the paging, cursor and epoch rules of the real one. */
final class FakeMlService implements MlRemoteService {

    static final Instant AT = Instant.parse("2021-11-20T14:03:05Z");

    private final long epoch;
    private final List<MlEventDto> events = new ArrayList<>();
    /** Events with a sequence up to this were "discarded" (simulates retention). */
    private long discardedUpTo;
    private boolean down;

    FakeMlService(long epoch) {
        this.epoch = epoch;
    }

    synchronized FakeMlService withPrediction(String dataType, double lower, double upper, String unit) {
        events.add(new PredictionChangeDto(events.size() + 1, AT, dataType, null,
                new PredictionDto(dataType, lower, upper, (lower + upper) / 2, 0.34, 480, unit)));
        return this;
    }

    synchronized FakeMlService withAnomaly(String chainId, double observed) {
        PhaseObservationDto phase = new PhaseObservationDto("temperature > 20.0", AT.minusSeconds(60), AT, 6);
        events.add(new AnomalyDto(events.size() + 1, AT, "temperature", chainId, "warm stays warm", phase, phase,
                "temperature > 20.0", observed, "CELSIUS", AT.plusSeconds(60)));
        return this;
    }

    synchronized void discardUpTo(long sequence) {
        discardedUpTo = sequence;
    }

    /** From now on, calls fail like calls to an unexported remote object (server stopped). */
    synchronized void shutDown() {
        down = true;
    }

    @Override
    public synchronized PollResult pollEvents(long afterSequence, int maxEvents) throws RemoteException {
        if (down) {
            throw new NoSuchObjectException("no such object in table");
        }
        List<MlEventDto> page = events.stream()
                .filter(e -> e.sequence() > afterSequence && e.sequence() > discardedUpTo)
                .limit(maxEvents)
                .toList();
        long remaining = events.stream().filter(e -> e.sequence() > afterSequence && e.sequence() > discardedUpTo).count();
        long next = page.isEmpty() ? Math.min(afterSequence, events.size()) : page.get(page.size() - 1).sequence();
        return new PollResult(epoch, page, next, afterSequence < discardedUpTo, remaining > page.size());
    }

    @Override
    public List<PredictionDto> getCurrentPredictions() {
        return List.of();
    }

    @Override
    public ServiceStatusDto getStatus() {
        return new ServiceStatusDto("ml-fake", epoch, AT, List.of("co2", "temperature"),
                Map.of("fog-temp", "ACTIVE (3 aggregates, last 1 s ago)", "fog-co2", "PENDING (no aggregates yet)"),
                0, 0, events.size());
    }
}
