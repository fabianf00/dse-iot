package at.ac.univie.dse.iot.msiot.generator;

/**
 * Physically plausible range of a sensor, e.g. CO2 can never be negative. {@code null} bounds are
 * open.
 */
public record ValueRange(Double min, Double max) {

    public static final ValueRange UNBOUNDED = new ValueRange(null, null);

    public ValueRange {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("min must not be greater than max");
        }
    }

    public double clamp(double value) {
        double result = value;
        if (min != null && result < min) {
            result = min;
        }
        if (max != null && result > max) {
            result = max;
        }
        return result;
    }
}
