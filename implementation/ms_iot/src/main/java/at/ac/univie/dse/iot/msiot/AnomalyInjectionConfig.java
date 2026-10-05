package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * Optional injection of sustained deviations, so the ML anomaly detection has something to find.
 *
 * @param enabled               off when missing
 * @param probabilityPerReading chance that an anomaly starts with a reading, in (0, 1]
 * @param offset                added to every value while the anomaly lasts
 * @param durationSeconds       how long an anomaly lasts
 */
public record AnomalyInjectionConfig(
        boolean enabled,
        double probabilityPerReading,
        double offset,
        long durationSeconds) implements ValidatableConfig {

    @Override
    public void validate(ConfigValidator validator) {
        if (enabled) {
            validator.check(probabilityPerReading > 0 && probabilityPerReading <= 1,
                            "probabilityPerReading", "must be > 0 and <= 1")
                    .positive("durationSeconds", durationSeconds);
        }
    }
}
