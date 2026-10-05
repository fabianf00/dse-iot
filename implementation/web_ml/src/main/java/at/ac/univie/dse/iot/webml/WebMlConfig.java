package at.ac.univie.dse.iot.webml;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ServiceConfig;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import java.net.URI;
import java.util.List;

/**
 * Configuration of a WEB ML instance.
 *
 * <pre>{@code
 * {
 *   "serviceId": "web-ml",
 *   "port": 50400,
 *   "endpoints": [ { "name": "ml-1", "restUrl": "http://127.0.0.1:50303/api/v1" } ],
 *   "refreshIntervalMs": 5000
 * }
 * }</pre>
 *
 * @param serviceId         instance id used in logs
 * @param bindHost          local address to bind, default {@value #DEFAULT_BIND_HOST}
 * @param port              TCP port of the web server
 * @param endpoints         MS ML REST APIs the browser can choose from (the first is preselected)
 * @param refreshIntervalMs how often the live view refreshes, default {@value #DEFAULT_REFRESH_INTERVAL_MS}
 */
public record WebMlConfig(String serviceId, String bindHost, int port, List<Endpoint> endpoints,
        long refreshIntervalMs) implements ServiceConfig {

    public static final String DEFAULT_BIND_HOST = "0.0.0.0";
    public static final long DEFAULT_REFRESH_INTERVAL_MS = 5_000;

    public WebMlConfig {
        bindHost = bindHost == null || bindHost.isBlank() ? DEFAULT_BIND_HOST : bindHost;
        endpoints = endpoints == null ? null : List.copyOf(endpoints);
        refreshIntervalMs = refreshIntervalMs == 0 ? DEFAULT_REFRESH_INTERVAL_MS : refreshIntervalMs;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("serviceId", serviceId)
                .port("port", port)
                .notEmpty("endpoints", endpoints);
        if (endpoints != null) {
            validator.each("endpoints", endpoints);
        }
        validator.inRange("refreshIntervalMs", refreshIntervalMs, 1_000, 3_600_000);
        if (endpoints != null) {
            validator.check(endpoints.stream().map(Endpoint::name).distinct().count() == endpoints.size(),
                    "endpoints", "must have unique names");
        }
    }

    /**
     * An MS ML REST API as seen from the browser.
     *
     * @param name    shown in the endpoint selector, e.g. {@code ml-1}
     * @param restUrl base URL including {@code /api/v1}; must be reachable from the browser
     */
    public record Endpoint(String name, String restUrl) implements ValidatableConfig {

        public Endpoint {
            restUrl = restUrl == null ? null : stripTrailingSlash(restUrl.strip());
        }

        @Override
        public void validate(ConfigValidator validator) {
            validator.notBlank("name", name).notBlank("restUrl", restUrl);
            if (restUrl != null && !restUrl.isBlank()) {
                validator.check(isHttpUrl(restUrl), "restUrl", "must be an absolute http:// or https:// URL");
            }
        }

        /** Scheme, host and port of {@link #restUrl()}, e.g. {@code http://127.0.0.1:50303}. */
        public String origin() {
            URI uri = URI.create(restUrl);
            return uri.getScheme() + "://" + uri.getRawAuthority();
        }

        private static boolean isHttpUrl(String url) {
            try {
                URI uri = URI.create(url);
                return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }
}
