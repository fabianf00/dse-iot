package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ServiceConfig;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import java.util.List;

/**
 * Config of CLNT ML, e.g. {@code config/clnt-ml.json}. Only {@code endpoints} is required.
 *
 * @param serviceId             name of this client in logs, default {@value #DEFAULT_SERVICE_ID}
 * @param pollIntervalMs        pause between two polls of an endpoint, default {@value #DEFAULT_POLL_INTERVAL_MS}
 * @param maxEventsPerPoll      events requested per call, at most {@value MlRemoteService#MAX_EVENTS_PER_POLL},
 *                              default {@value #DEFAULT_MAX_EVENTS_PER_POLL}
 * @param connectTimeoutMs      TCP connect timeout for RMI calls, default {@value #DEFAULT_CONNECT_TIMEOUT_MS}
 * @param reconnectBackoffMaxMs longest pause between reconnect attempts, default {@value #DEFAULT_RECONNECT_BACKOFF_MAX_MS}
 * @param endpoints             the ML instances to poll
 */
public record ClientConfig(
        String serviceId,
        long pollIntervalMs,
        int maxEventsPerPoll,
        long connectTimeoutMs,
        long reconnectBackoffMaxMs,
        List<EndpointConfig> endpoints) implements ServiceConfig {

    public static final String DEFAULT_SERVICE_ID = "clnt-ml";
    public static final long DEFAULT_POLL_INTERVAL_MS = 5_000;
    public static final int DEFAULT_MAX_EVENTS_PER_POLL = 100;
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final long DEFAULT_RECONNECT_BACKOFF_MAX_MS = 30_000;

    public ClientConfig {
        serviceId = serviceId == null || serviceId.isBlank() ? DEFAULT_SERVICE_ID : serviceId;
        pollIntervalMs = pollIntervalMs == 0 ? DEFAULT_POLL_INTERVAL_MS : pollIntervalMs;
        maxEventsPerPoll = maxEventsPerPoll == 0 ? DEFAULT_MAX_EVENTS_PER_POLL : maxEventsPerPoll;
        connectTimeoutMs = connectTimeoutMs == 0 ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
        reconnectBackoffMaxMs = reconnectBackoffMaxMs == 0 ? DEFAULT_RECONNECT_BACKOFF_MAX_MS : reconnectBackoffMaxMs;
        endpoints = endpoints == null ? null : List.copyOf(endpoints);
    }

    /** This config with another poll interval (command line override). */
    public ClientConfig withPollInterval(long intervalMs) {
        return new ClientConfig(serviceId, intervalMs, maxEventsPerPoll, connectTimeoutMs, reconnectBackoffMaxMs, endpoints);
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.positive("pollIntervalMs", pollIntervalMs)
                .inRange("maxEventsPerPoll", maxEventsPerPoll, 1, MlRemoteService.MAX_EVENTS_PER_POLL)
                .positive("connectTimeoutMs", connectTimeoutMs)
                .check(reconnectBackoffMaxMs >= 1_000, "reconnectBackoffMaxMs", "must be >= 1000")
                .notEmpty("endpoints", endpoints)
                .each("endpoints", endpoints);
        if (endpoints != null) {
            validator.check(endpoints.stream().map(EndpointConfig::name).distinct().count() == endpoints.size(),
                    "endpoints", "must have unique names");
        }
    }

    /**
     * An ML instance to poll: {@code rmi://<host>:<registryPort>/<bindName>}.
     *
     * @param name         label in the output, e.g. {@code ml-1}
     * @param registryPort default 1099
     * @param bindName     default {@value MlRemoteService#DEFAULT_BIND_NAME}
     */
    public record EndpointConfig(String name, String host, int registryPort, String bindName) implements ValidatableConfig {

        public EndpointConfig {
            registryPort = registryPort == 0 ? 1099 : registryPort;
            bindName = bindName == null || bindName.isBlank() ? MlRemoteService.DEFAULT_BIND_NAME : bindName;
        }

        public String url() {
            return "rmi://" + host + ":" + registryPort + "/" + bindName;
        }

        @Override
        public void validate(ConfigValidator validator) {
            validator.notBlank("name", name).notBlank("host", host).port("registryPort", registryPort);
        }
    }
}
