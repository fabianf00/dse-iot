package at.ac.univie.dse.iot.msml.data;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated values per data type, ordered by receive time. Values older than the retention
 * period are discarded. Thread-safe: workers append and read concurrently.
 *
 * <p>Receive time is used instead of the fog's window times because fog and ML may run on
 * different hosts with different clocks.
 */
public final class TimeSeriesStore {

    /** Orders points by time; the counter keeps points with the same timestamp apart. */
    private record Key(Instant at, long order) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byTime = at.compareTo(other.at);
            return byTime != 0 ? byTime : Long.compare(order, other.order);
        }
    }

    private final Duration retention;
    private final Map<DataType, ConcurrentSkipListMap<Key, Double>> series = new ConcurrentHashMap<>();
    private final Map<DataType, Unit> units = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();

    public TimeSeriesStore(Duration retention) {
        this.retention = retention;
    }

    public void append(DataType dataType, Unit unit, Instant at, double value) {
        ConcurrentSkipListMap<Key, Double> points = series.computeIfAbsent(dataType, t -> new ConcurrentSkipListMap<>());
        points.put(new Key(at, order.incrementAndGet()), value);
        units.put(dataType, unit);
        points.headMap(new Key(at.minus(retention), Long.MIN_VALUE)).clear();
    }

    /** Points of {@code dataType} with {@code from <= at <= to}, oldest first. */
    public List<DataPoint> range(DataType dataType, Instant from, Instant to) {
        ConcurrentSkipListMap<Key, Double> points = series.get(dataType);
        if (points == null || from.isAfter(to)) {
            return List.of();
        }
        return points.subMap(new Key(from, Long.MIN_VALUE), true, new Key(to, Long.MAX_VALUE), true)
                .entrySet().stream()
                .map(entry -> new DataPoint(entry.getKey().at(), entry.getValue()))
                .toList();
    }

    public Optional<DataPoint> latest(DataType dataType) {
        ConcurrentSkipListMap<Key, Double> points = series.get(dataType);
        if (points == null) {
            return Optional.empty();
        }
        Map.Entry<Key, Double> last = points.lastEntry();
        return last == null ? Optional.empty() : Optional.of(new DataPoint(last.getKey().at(), last.getValue()));
    }

    /** Unit of the most recent value of {@code dataType}. */
    public Optional<Unit> unitOf(DataType dataType) {
        return Optional.ofNullable(units.get(dataType));
    }

    public Set<DataType> dataTypes() {
        return Set.copyOf(series.keySet());
    }
}
