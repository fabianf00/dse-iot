package at.ac.univie.dse.iot.msiot.sensor;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msiot.generator.ValueGenerator;
import at.ac.univie.dse.iot.msiot.generator.ValueRange;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns generated values into {@link SensorReading}s.
 *
 * <p>Values are clamped to the sensor's plausible range. A non-finite value (broken generator) is
 * skipped and logged, so an invalid reading is never published. The sequence number counts
 * published readings only, so consumers see gaps only when readings are lost on the way.
 */
public final class SimulatedSensor implements Sensor {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatedSensor.class);

    private final String deviceId;
    private final DataType dataType;
    private final Unit unit;
    private final ValueGenerator generator;
    private final ValueRange range;
    private final Clock clock;
    private final UUID bootId = UUID.randomUUID();
    private long nextSequence;

    public SimulatedSensor(String deviceId, DataType dataType, Unit unit, ValueGenerator generator,
            ValueRange range, Clock clock) {
        this.deviceId = deviceId;
        this.dataType = dataType;
        this.unit = unit;
        this.generator = generator;
        this.range = range;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<SensorReading> read() {
        Instant now = clock.instant();
        double raw = generator.next(now);
        if (!Double.isFinite(raw)) {
            LOG.warn("Generator of {} produced {}, reading skipped", deviceId, raw);
            return Optional.empty();
        }
        double value = range.clamp(raw);
        return Optional.of(new SensorReading(deviceId, dataType, unit, value, nextSequence++, bootId, now.toEpochMilli()));
    }

    /** Random id of this sensor instance; changes on every device start. */
    public UUID bootId() {
        return bootId;
    }
}
