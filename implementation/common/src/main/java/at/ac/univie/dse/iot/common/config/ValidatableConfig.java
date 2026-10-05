package at.ac.univie.dse.iot.common.config;

/**
 * A configuration object that can check its own values.
 *
 * <p>Implementations report every problem to the validator instead of throwing on the first
 * one, so a user sees all mistakes in a config file at once. Nested configs are validated
 * through {@link ConfigValidator#nested(String, ValidatableConfig)}.
 */
public interface ValidatableConfig {

    void validate(ConfigValidator validator);
}
