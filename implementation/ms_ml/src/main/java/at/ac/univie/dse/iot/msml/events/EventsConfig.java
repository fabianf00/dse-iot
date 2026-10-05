package at.ac.univie.dse.iot.msml.events;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * Event log settings.
 *
 * @param retention events kept for polling clients, default {@value #DEFAULT_RETENTION}
 */
public record EventsConfig(int retention) implements ValidatableConfig {

    public static final int DEFAULT_RETENTION = 10_000;

    public EventsConfig {
        retention = retention == 0 ? DEFAULT_RETENTION : retention;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.positive("retention", retention);
    }
}
