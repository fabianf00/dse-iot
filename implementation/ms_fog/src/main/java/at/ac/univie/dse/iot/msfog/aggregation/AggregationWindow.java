package at.ac.univie.dse.iot.msfog.aggregation;

import at.ac.univie.dse.iot.common.model.SensorReading;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Readings collected during one window.
 *
 * <p>Once {@link #close} was called, {@link #add} refuses further readings. Together with the
 * swap in {@link SensorReadingCollector} this guarantees that every reading is counted in exactly
 * one window, even if it arrives while the window is being closed.
 */
public final class AggregationWindow {

    /** Contents of a closed window. */
    public record Snapshot(long openedAt, long closedAt, List<Double> values, int deviceCount) {
    }

    private final long openedAt;
    private final List<Double> values = new ArrayList<>();
    private final Set<String> devices = new HashSet<>();
    private boolean closed;

    public AggregationWindow(long openedAt) {
        this.openedAt = openedAt;
    }

    /** Adds a reading; returns {@code false} if the window is already closed. */
    public synchronized boolean add(SensorReading reading) {
        if (closed) {
            return false;
        }
        values.add(reading.value());
        devices.add(reading.deviceId());
        return true;
    }

    /** Closes the window and returns its contents. */
    public synchronized Snapshot close(long closedAt) {
        closed = true;
        return new Snapshot(openedAt, closedAt, List.copyOf(values), devices.size());
    }

    public long openedAt() {
        return openedAt;
    }
}
