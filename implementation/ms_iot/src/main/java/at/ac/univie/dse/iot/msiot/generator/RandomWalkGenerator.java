package at.ac.univie.dse.iot.msiot.generator;

import java.time.Instant;
import java.util.Random;

/**
 * Mean-reverting random walk: each step moves the value by {@code N(0, stepStdDev)} and pulls it
 * back towards {@code base} by the fraction {@code meanReversion} (0 = pure random walk,
 * 1 = noise around base). The walk itself stays inside {@code range}, so it cannot drift off and
 * stick to a bound.
 */
public final class RandomWalkGenerator implements ValueGenerator {

    private final double base;
    private final double stepStdDev;
    private final double meanReversion;
    private final ValueRange range;
    private final Random random;
    private double current;

    public RandomWalkGenerator(double base, double stepStdDev, double meanReversion, ValueRange range, Random random) {
        if (meanReversion < 0 || meanReversion > 1) {
            throw new IllegalArgumentException("meanReversion must be between 0 and 1");
        }
        this.base = base;
        this.stepStdDev = stepStdDev;
        this.meanReversion = meanReversion;
        this.range = range;
        this.random = random;
        this.current = range.clamp(base);
    }

    @Override
    public double next(Instant at) {
        double step = random.nextGaussian() * stepStdDev + meanReversion * (base - current);
        current = range.clamp(current + step);
        return current;
    }
}
