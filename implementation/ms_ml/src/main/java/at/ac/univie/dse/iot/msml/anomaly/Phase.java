package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.DataType;
import java.time.Duration;
import java.time.Instant;

/**
 * The past or current phase of a behaviour chain: at time {@code t}, every value of
 * {@code dataType} received in {@code [t - fromSec, t - toSec]} satisfies {@code condition}.
 * Example: {@code fromSec 1200, toSec 600} means "10 to 20 minutes ago".
 */
public record Phase(DataType dataType, ThresholdCondition condition, long fromSec, long toSec)
        implements ValidatableConfig {

    /** Start of the phase relative to {@code now}. */
    public Instant startAt(Instant now) {
        return now.minus(Duration.ofSeconds(fromSec));
    }

    /** End of the phase relative to {@code now}. */
    public Instant endAt(Instant now) {
        return now.minus(Duration.ofSeconds(toSec));
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("dataType", dataType)
                .nested("condition", condition)
                .nonNegative("toSec", toSec)
                .check(fromSec > toSec, "fromSec", "must be > toSec");
    }
}
