package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * Database of the historic ML results.
 *
 * @param jdbcUrl               JDBC URL, e.g. {@code jdbc:h2:file:./run/data/ml-1} (relative to the
 *                              working directory); by default derived from the service id
 * @param user                  database user, default {@value #DEFAULT_USER}
 * @param password              database password, default empty
 * @param recorderQueueCapacity events waiting to be written, default {@value #DEFAULT_RECORDER_QUEUE_CAPACITY}
 * @param poolSize              database connections, default {@value #DEFAULT_POOL_SIZE}
 */
public record StorageConfig(String jdbcUrl, String user, String password, int recorderQueueCapacity, int poolSize)
        implements ValidatableConfig {

    public static final String DEFAULT_USER = "sa";
    public static final int DEFAULT_RECORDER_QUEUE_CAPACITY = 10_000;
    public static final int DEFAULT_POOL_SIZE = 5;

    public StorageConfig {
        user = user == null ? DEFAULT_USER : user;
        password = password == null ? "" : password;
        recorderQueueCapacity = recorderQueueCapacity == 0 ? DEFAULT_RECORDER_QUEUE_CAPACITY : recorderQueueCapacity;
        poolSize = poolSize == 0 ? DEFAULT_POOL_SIZE : poolSize;
    }

    /** The default storage of a service: an H2 file database under {@code run/data/<serviceId>}. */
    public static StorageConfig defaultFor(String serviceId) {
        return new StorageConfig(defaultUrl(serviceId), null, null, 0, 0);
    }

    /** This config with the default URL of {@code serviceId} if it has none. */
    public StorageConfig withDefaultUrl(String serviceId) {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return this;
        }
        return new StorageConfig(defaultUrl(serviceId), user, password, recorderQueueCapacity, poolSize);
    }

    private static String defaultUrl(String serviceId) {
        return "jdbc:h2:file:./run/data/" + serviceId;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("jdbcUrl", jdbcUrl)
                .check(jdbcUrl == null || jdbcUrl.startsWith("jdbc:"), "jdbcUrl", "must start with jdbc:")
                .positive("recorderQueueCapacity", recorderQueueCapacity)
                .positive("poolSize", poolSize);
    }
}
