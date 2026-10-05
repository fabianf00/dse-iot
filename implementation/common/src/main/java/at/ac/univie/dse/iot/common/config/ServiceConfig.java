package at.ac.univie.dse.iot.common.config;

/** Root configuration of a microservice or client instance. */
public interface ServiceConfig extends ValidatableConfig {

    /** Unique id of this instance, e.g. {@code iot-temp-1}. Used in logs and messages. */
    String serviceId();
}
