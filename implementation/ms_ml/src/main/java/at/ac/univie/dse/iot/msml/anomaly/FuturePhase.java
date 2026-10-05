package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.DataType;
import java.time.Duration;

/**
 * The expected future of a behaviour chain: every value of {@code dataType} received within
 * {@code withinSec} after the expectation was created satisfies {@code condition}.
 */
public record FuturePhase(DataType dataType, ThresholdCondition condition, long withinSec) implements ValidatableConfig {

    public Duration within() {
        return Duration.ofSeconds(withinSec);
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("dataType", dataType)
                .nested("condition", condition)
                .positive("withinSec", withinSec);
    }
}
