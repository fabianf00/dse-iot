package at.ac.univie.dse.iot.msml.ingest;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * A fog node to subscribe to.
 *
 * @param id   the fog's {@code serviceId}; aggregates carry it as {@code fogId}
 * @param host host of the fog's UDP API
 * @param port port of the fog's UDP API
 */
public record FogEndpoint(String id, String host, int port) implements ValidatableConfig {

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("id", id).notBlank("host", host).port("port", port);
    }
}
