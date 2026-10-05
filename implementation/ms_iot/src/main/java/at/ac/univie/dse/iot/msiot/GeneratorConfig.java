package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * How a device generates its values. Which fields are used depends on {@code kind}:
 *
 * <ul>
 *   <li>{@code CONSTANT}: {@code base}</li>
 *   <li>{@code SINE}: {@code base}, {@code amplitude}, {@code periodSeconds}, {@code phaseSeconds}, {@code noiseStdDev}</li>
 *   <li>{@code RANDOM_WALK}: {@code base} (start and mean), {@code stepStdDev}, {@code meanReversion} (0..1)</li>
 * </ul>
 *
 * @param seed seed of the random numbers; {@code null} for a different sequence on every start
 * @param min  lowest plausible value, {@code null} for none; values are clamped
 * @param max  highest plausible value, {@code null} for none; values are clamped
 */
public record GeneratorConfig(
        Kind kind,
        double base,
        double amplitude,
        double periodSeconds,
        double phaseSeconds,
        double noiseStdDev,
        double stepStdDev,
        double meanReversion,
        Long seed,
        Double min,
        Double max) implements ValidatableConfig {

    public enum Kind {
        CONSTANT,
        SINE,
        RANDOM_WALK
    }

    public static GeneratorConfig constant(double value) {
        return new GeneratorConfig(Kind.CONSTANT, value, 0, 0, 0, 0, 0, 0, null, null, null);
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("kind", kind);
        if (min != null && max != null) {
            validator.check(min <= max, "max", "must be >= min");
        }
        if (kind == Kind.SINE) {
            validator.check(amplitude >= 0, "amplitude", "must be >= 0")
                    .positive("periodSeconds", periodSeconds)
                    .check(noiseStdDev >= 0, "noiseStdDev", "must be >= 0");
        } else if (kind == Kind.RANDOM_WALK) {
            validator.positive("stepStdDev", stepStdDev)
                    .check(meanReversion >= 0 && meanReversion <= 1, "meanReversion", "must be between 0 and 1");
        }
    }
}
