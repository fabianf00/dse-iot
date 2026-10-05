package at.ac.univie.dse.iot.msml.events;

import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.prediction.PredictionChange;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Append-only log of anomalies and prediction changes, read by polling clients.
 *
 * <p>Events get consecutive sequence numbers. A client asks for everything after the last
 * sequence it saw; it needs no clock and cannot miss events, unless it falls behind the retention
 * limit, which the answer then reports. The {@link #epoch()} identifies this log instance, so
 * clients notice a restart (sequences start at 1 again).
 */
public final class EventLog {

    /** A page of events after a cursor. */
    public record Page(List<MlEvent> events, long nextCursor, boolean truncated, boolean hasMore) {
    }

    private final int retention;
    private final Clock clock;
    private final long epoch;
    private final Deque<MlEvent> events = new ArrayDeque<>();
    private long lastSequence;

    public EventLog(int retention, Clock clock) {
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be > 0");
        }
        this.retention = retention;
        this.clock = clock;
        this.epoch = clock.millis();
    }

    public synchronized MlEvent.AnomalyEvent append(Anomaly anomaly) {
        return add(new MlEvent.AnomalyEvent(++lastSequence, clock.instant(), anomaly));
    }

    public synchronized MlEvent.PredictionChangedEvent append(PredictionChange change) {
        return add(new MlEvent.PredictionChangedEvent(++lastSequence, clock.instant(), change));
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

    private <E extends MlEvent> E add(E event) {
        events.addLast(event);
        if (events.size() > retention) {
            events.removeFirst();
        }
        return event;
    }
}
