package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import java.util.List;

/**
 * REST API of MS ML.
 *
 * @param host           local address to bind, default {@value #DEFAULT_HOST}
 * @param port           TCP port, default {@value #DEFAULT_PORT}; 0 picks a free port (tests)
 * @param allowedOrigins browser origins allowed to call the API (CORS), by default the WEB ML
 *                       origins; an empty list disables CORS
 */
public record RestConfig(String host, int port, List<String> allowedOrigins) implements ValidatableConfig {

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 50303;
    public static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of("http://127.0.0.1:50400", "http://localhost:50400");

    public RestConfig {
        host = host == null || host.isBlank() ? DEFAULT_HOST : host;
        allowedOrigins = allowedOrigins == null ? DEFAULT_ALLOWED_ORIGINS : List.copyOf(allowedOrigins);
    }

    /** Defaults: all interfaces, port {@value #DEFAULT_PORT}, WEB ML origins. */
    public static RestConfig defaults() {
        return new RestConfig(null, DEFAULT_PORT, null);
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.inRange("port", port, 0, 65535);
        for (int i = 0; i < allowedOrigins.size(); i++) {
            String origin = allowedOrigins.get(i);
            validator.check(origin != null && (origin.startsWith("http://") || origin.startsWith("https://")),
                    "allowedOrigins[" + i + "]", "must start with http:// or https://");
        }
    }
}
