package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ServiceConfig;

/**
 * Root config of an MS IoT instance (SIMI: one file per instance, e.g. {@code config/iot-temp-1.json}).
 *
 * @param serviceId unique id of the device, also used as its {@code deviceId} and, unless
 *                  {@code cf.nodeId} is set, as its CF node id
 * @param sensor    the device's sensor
 * @param cf        Communication Framework settings
 */
public record IotConfig(String serviceId, SensorConfig sensor, CfConfig cf) implements ServiceConfig {

    public IotConfig {
        if (cf != null) {
            cf = cf.withDefaultNodeId(serviceId);
        }
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("serviceId", serviceId)
                .nested("sensor", sensor)
                .nested("cf", cf);
    }
}
