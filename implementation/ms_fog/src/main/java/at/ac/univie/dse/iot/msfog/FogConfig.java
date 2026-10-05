package at.ac.univie.dse.iot.msfog;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ServiceConfig;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;

/**
 * Root config of an MS Fog instance (SIMI: one instance per data type, e.g.
 * {@code config/fog-temp.json}).
 *
 * @param serviceId               unique id, also the CF node id unless {@code cf.nodeId} is set
 * @param dataType                the only data type this instance aggregates
 * @param unit                    unit readings must have; others are dropped
 * @param aggregationMode         AVG, MAX or MIN
 * @param windowMs                length of a collection window
 * @param emptyWindowWarningAfter warn after this many empty windows in a row, default {@value #DEFAULT_EMPTY_WINDOW_WARNING_AFTER}
 * @param udp                     the UDP aggregate API
 * @param cf                      Communication Framework settings
 */
public record FogConfig(
        String serviceId,
        DataType dataType,
        Unit unit,
        AggregationMode aggregationMode,
        long windowMs,
        int emptyWindowWarningAfter,
        UdpConfig udp,
        CfConfig cf) implements ServiceConfig {

    public static final int DEFAULT_EMPTY_WINDOW_WARNING_AFTER = 3;

    public FogConfig {
        emptyWindowWarningAfter = emptyWindowWarningAfter == 0 ? DEFAULT_EMPTY_WINDOW_WARNING_AFTER : emptyWindowWarningAfter;
        if (cf != null) {
            cf = cf.withDefaultNodeId(serviceId);
        }
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("serviceId", serviceId)
                .notNull("dataType", dataType)
                .notNull("unit", unit)
                .notNull("aggregationMode", aggregationMode)
                .positive("windowMs", windowMs)
                .positive("emptyWindowWarningAfter", emptyWindowWarningAfter)
                .nested("udp", udp)
                .nested("cf", cf);
    }

    /**
     * The UDP Fog Aggregate API.
     *
     * @param bindHost       local address to bind, default {@value #DEFAULT_BIND_HOST}
     * @param port           UDP port clients send requests to
     * @param maxLeaseMs     longest lease granted, default {@value #DEFAULT_MAX_LEASE_MS}
     * @param maxSubscribers subscribers served at the same time, default {@value #DEFAULT_MAX_SUBSCRIBERS}
     */
    public record UdpConfig(String bindHost, int port, long maxLeaseMs, int maxSubscribers) implements ValidatableConfig {

        public static final String DEFAULT_BIND_HOST = "0.0.0.0";
        public static final long DEFAULT_MAX_LEASE_MS = 120_000;
        public static final int DEFAULT_MAX_SUBSCRIBERS = 32;

        public UdpConfig {
            bindHost = bindHost == null || bindHost.isBlank() ? DEFAULT_BIND_HOST : bindHost;
            maxLeaseMs = maxLeaseMs == 0 ? DEFAULT_MAX_LEASE_MS : maxLeaseMs;
            maxSubscribers = maxSubscribers == 0 ? DEFAULT_MAX_SUBSCRIBERS : maxSubscribers;
        }

        @Override
        public void validate(ConfigValidator validator) {
            validator.port("port", port)
                    .positive("maxLeaseMs", maxLeaseMs)
                    .positive("maxSubscribers", maxSubscribers);
        }
    }
}
