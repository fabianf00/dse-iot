package at.ac.univie.dse.iot.msiot.generator;

import java.time.Instant;

/** Always returns the same value. Useful for tests and calibration. */
public final class ConstantGenerator implements ValueGenerator {

    private final double value;

    public ConstantGenerator(double value) {
        this.value = value;
    }

    @Override
    public double next(Instant at) {
        return value;
    }
}
