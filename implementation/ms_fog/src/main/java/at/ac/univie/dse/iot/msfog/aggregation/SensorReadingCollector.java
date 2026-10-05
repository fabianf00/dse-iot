package at.ac.univie.dse.iot.msfog.aggregation;

import at.ac.univie.dse.iot.cf.CfMessage;
import at.ac.univie.dse.iot.cf.MessageListener;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives sensor readings from the CF and adds them to the current window.
 *
 * <p>Only readings of the configured data type and unit are accepted; others are dropped with a
 * warning. Readings are assigned to windows by the time they arrive here, not by their
 * {@code measuredAt}, because device clocks are not synchronized.
 */
public final class SensorReadingCollector implements MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(SensorReadingCollector.class);

    private final DataType dataType;
    private final Unit unit;
    private final LongSupplier clock;
    private final AtomicReference<AggregationWindow> current;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public SensorReadingCollector(DataType dataType, Unit unit, LongSupplier clock) {
        this.dataType = dataType;
        this.unit = unit;
        this.clock = clock;
        this.current = new AtomicReference<>(new AggregationWindow(clock.getAsLong()));
    }

    @Override
    public void onMessage(CfMessage message) {
        SensorReading reading;
        try {
            reading = message.payloadAs(SensorReading.class);
        } catch (RuntimeException e) {
            reject("Dropped invalid reading from {}: {}", message.origin(), e.getMessage());
            return;
        }
        if (!reading.dataType().equals(dataType)) {
            reject("Dropped {} reading from {}: this fog aggregates {}", reading.dataType(), reading.deviceId(), dataType);
            return;
        }
        if (reading.unit() != unit) {
            reject("Dropped reading in {} from {}: expected {}", reading.unit(), reading.deviceId(), unit);
            return;
        }
        add(reading);
    }

    /** Adds a reading to the current window. */
    public void add(SensorReading reading) {
        // If the window was closed between get() and add(), the next get() returns its successor.
        while (!current.get().add(reading)) {
            Thread.onSpinWait();
        }
        accepted.incrementAndGet();
    }

    /** Starts a new window and returns the contents of the one it replaces. */
    public AggregationWindow.Snapshot closeWindow() {
        long now = clock.getAsLong();
        AggregationWindow previous = current.getAndSet(new AggregationWindow(now));
        return previous.close(now);
    }

    public long acceptedCount() {
        return accepted.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    private void reject(String format, Object... arguments) {
        rejected.incrementAndGet();
        LOG.warn(format, arguments);
    }
}
