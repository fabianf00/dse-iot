package at.ac.univie.dse.iot.msml.ingest;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * UDP settings of MS ML as a client of the Fog Aggregate API.
 *
 * @param bindHost            local address to bind, default {@value #DEFAULT_BIND_HOST}
 * @param callbackPort        port aggregates are sent to (and requests are sent from)
 * @param callbackHost        host the fogs should send aggregates to; {@code null} lets each fog use
 *                            the source address of the request
 * @param leaseMs             lease requested from the fogs, default {@value #DEFAULT_LEASE_MS}; renewed at half time
 * @param subscribeRetryMaxMs longest pause between subscription attempts, default {@value #DEFAULT_SUBSCRIBE_RETRY_MAX_MS}
 */
public record FogClientConfig(String bindHost, int callbackPort, String callbackHost, long leaseMs,
        long subscribeRetryMaxMs) implements ValidatableConfig {

    public static final String DEFAULT_BIND_HOST = "0.0.0.0";
    public static final long DEFAULT_LEASE_MS = 30_000;
    public static final long DEFAULT_SUBSCRIBE_RETRY_MAX_MS = 30_000;

    public FogClientConfig {
        bindHost = bindHost == null || bindHost.isBlank() ? DEFAULT_BIND_HOST : bindHost;
        leaseMs = leaseMs == 0 ? DEFAULT_LEASE_MS : leaseMs;
        subscribeRetryMaxMs = subscribeRetryMaxMs == 0 ? DEFAULT_SUBSCRIBE_RETRY_MAX_MS : subscribeRetryMaxMs;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.port("callbackPort", callbackPort)
                .check(leaseMs >= 1_000, "leaseMs", "must be >= 1000")
                .check(subscribeRetryMaxMs >= 1_000, "subscribeRetryMaxMs", "must be >= 1000");
    }
}
