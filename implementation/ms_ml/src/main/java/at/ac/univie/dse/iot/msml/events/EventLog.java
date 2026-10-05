package at.ac.univie.dse.iot.msml.events;

import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.prediction.PredictionChange;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only log of anomalies and prediction changes, read by polling clients.
 *
 * <p>Events get consecutive sequence numbers. A client asks for everything after the last
 * sequence it saw; it needs no clock and cannot miss events, unless it falls behind the retention
 * limit, which the answer then reports. The {@link #epoch()} identifies this log instance, so
 * clients notice a restart (sequences start at 1 again).
 *
 * <p>Listeners (e.g. the recorder that stores events in the database) get every appended event
 * once, after the append and outside the log's lock, so a slow listener never blocks other writers.
 */
public final class EventLog {

    private static final Logger LOG = LoggerFactory.getLogger(EventLog.class);

    /** A page of events after a cursor. */
    public record Page(List<MlEvent> events, long nextCursor, boolean truncated, boolean hasMore) {
    }

    private final int retention;
    private final Clock clock;
    private final long epoch;
    private final Deque<MlEvent> events = new ArrayDeque<>();
    private final List<Consumer<MlEvent>> listeners = new CopyOnWriteArrayList<>();
    private long lastSequence;

    public EventLog(int retention, Clock clock) {
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be > 0");
        }
        this.retention = retention;
        this.clock = clock;
        this.epoch = clock.millis();
    }

    /** Registers a listener for every event appended from now on. It must not block for long. */
    public void addListener(Consumer<MlEvent> listener) {
        listeners.add(listener);
    }

    public MlEvent.AnomalyEvent append(Anomaly anomaly) {
        MlEvent.AnomalyEvent event;
        synchronized (this) {
            event = add(new MlEvent.AnomalyEvent(++lastSequence, clock.instant(), anomaly));
        }
        notifyListeners(event);
        return event;
    }

    public MlEvent.PredictionChangedEvent append(PredictionChange change) {
        MlEvent.PredictionChangedEvent event;
        synchronized (this) {
            event = add(new MlEvent.PredictionChangedEvent(++lastSequence, clock.instant(), change));
        }
        notifyListeners(event);
        return event;
    }

    /**
     * Up to {@code max} events with a sequence greater than {@code cursor}, oldest first.
     */
    public synchronized Page since(long cursor, int max) {
        List<MlEvent> page = new ArrayList<>();
        boolean hasMore = false;
        for (MlEvent event : events) {
            if (event.sequence() <= cursor) {
                continue;
            }
            if (page.size() == max) {
                hasMore = true;
                break;
            }
            page.add(event);
        }
        long oldestRetained = events.isEmpty() ? lastSequence + 1 : events.peekFirst().sequence();
        boolean truncated = cursor + 1 < oldestRetained && cursor < lastSequence;
        long nextCursor = page.isEmpty() ? Math.min(cursor, lastSequence) : page.get(page.size() - 1).sequence();
        return new Page(List.copyOf(page), nextCursor, truncated, hasMore);
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }

    /** Creation time of this log in epoch milliseconds; identifies the service instance. */
    public long epoch() {
        return epoch;
    }

    private void notifyListeners(MlEvent event) {
        for (Consumer<MlEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOG.error("Event listener failed on event {}", event.sequence(), e);
            }
        }
    }

    private <E extends MlEvent> E add(E event) {
        events.addLast(event);
        if (events.size() > retention) {
            events.removeFirst();
        }
        return event;
    }
}
