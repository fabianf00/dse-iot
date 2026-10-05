package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;

/**
 * The single sensor of a device.
 *
 * @param dataType          type of data the sensor measures; also selects the topic {@code sensor.<dataType>}
 * @param unit              unit of the values
 * @param publishIntervalMs time between two readings
 * @param generator         how values are generated
 * @param anomalyInjection  optional, {@code null} for none
 */
public record SensorConfig(
        DataType dataType,
        Unit unit,
        long publishIntervalMs,
        GeneratorConfig generator,
        AnomalyInjectionConfig anomalyInjection) implements ValidatableConfig {

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("dataType", dataType)
                .notNull("unit", unit)
                .positive("publishIntervalMs", publishIntervalMs)
                .nested("generator", generator);
        if (anomalyInjection != null) {
            validator.nested("anomalyInjection", anomalyInjection);
        }
    }
}
