package at.ac.univie.dse.iot.msiot.generator;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds rare, sustained deviations to any generator (Decorator), so the anomaly detection of MS ML
 * has something to find. With probability {@code probabilityPerReading} per reading an anomaly
 * starts; for {@code duration} every value is shifted by {@code offset}.
 */
public final class AnomalyInjectingGenerator implements ValueGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyInjectingGenerator.class);

    private final ValueGenerator delegate;
    private final double probabilityPerReading;
    private final double offset;
    private final Duration duration;
    private final Random random;
    private Instant anomalyEndsAt;

    public AnomalyInjectingGenerator(ValueGenerator delegate, double probabilityPerReading, double offset,
            Duration duration, Random random) {
        this.delegate = delegate;
        this.probabilityPerReading = probabilityPerReading;
        this.offset = offset;
        this.duration = duration;
        this.random = random;
    }

    @Override
    public double next(Instant at) {
        double value = delegate.next(at);
        if (anomalyEndsAt != null && at.isBefore(anomalyEndsAt)) {
            return value + offset;
        }
        if (anomalyEndsAt != null) {
            LOG.info("Injected anomaly ended");
            anomalyEndsAt = null;
        }
        if (random.nextDouble() < probabilityPerReading) {
            anomalyEndsAt = at.plus(duration);
            LOG.info("Injecting anomaly: offset {} for {} s", offset, duration.toSeconds());
            return value + offset;
        }
        return value;
    }

    /** Whether the last value was shifted. */
    public boolean isAnomalyActive() {
        return anomalyEndsAt != null;
    }
}
