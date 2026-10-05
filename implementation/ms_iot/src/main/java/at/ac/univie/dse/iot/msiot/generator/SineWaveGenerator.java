package at.ac.univie.dse.iot.msiot.generator;

import java.time.Instant;
import java.util.Random;

/**
 * Periodic signal with Gaussian noise, e.g. a daily temperature cycle or a room's CO2 level
 * following occupancy:
 * {@code base + amplitude * sin(2π * (t + phase) / period) + N(0, noiseStdDev)}.
 *
 * <p>{@code t} is wall-clock time, so all devices with the same period are in step and
 * {@code phase} shifts one device against the others.
 */
public final class SineWaveGenerator implements ValueGenerator {

    private final double base;
    private final double amplitude;
    private final double periodSeconds;
    private final double phaseSeconds;
    private final double noiseStdDev;
    private final Random random;

    public SineWaveGenerator(double base, double amplitude, double periodSeconds, double phaseSeconds,
            double noiseStdDev, Random random) {
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("periodSeconds must be > 0");
        }
        this.base = base;
        this.amplitude = amplitude;
        this.periodSeconds = periodSeconds;
        this.phaseSeconds = phaseSeconds;
        this.noiseStdDev = noiseStdDev;
        this.random = random;
    }

    @Override
    public double next(Instant at) {
        double seconds = at.toEpochMilli() / 1000.0;
        double angle = 2 * Math.PI * (seconds + phaseSeconds) / periodSeconds;
        double noise = noiseStdDev == 0 ? 0 : random.nextGaussian() * noiseStdDev;
        return base + amplitude * Math.sin(angle) + noise;
    }
}
