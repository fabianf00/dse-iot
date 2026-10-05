package at.ac.univie.dse.iot.testsupport;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal HTTP client for REST API tests: sends requests relative to a base URL and returns status,
 * body and headers. JSON bodies are parsed on demand.
 */
public final class RestTestClient {

    /** An HTTP response. */
    public record Response(int status, String body, HttpHeaders headers) {

        /** The body as JSON tree. */
        public JsonNode json() {
            return JsonCodec.shared().readTree(body);
        }

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }

        public String contentType() {
            return header("Content-Type").orElse("");
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;

    /** @param baseUrl e.g. {@code http://127.0.0.1:50303/api/v1} */
    public RestTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Response get(String path, String... headers) {
        return send("GET", path, null, null, headers);
    }

    public Response delete(String path) {
        return send("DELETE", path, null, null);
    }

    public Response post(String path, Object jsonBody) {
        return send("POST", path, json(jsonBody), "application/json");
    }

    public Response put(String path, Object jsonBody) {
        return send("PUT", path, json(jsonBody), "application/json");
    }

    public Response options(String path, String... headers) {
        return send("OPTIONS", path, null, null, headers);
    }

    /**
     * Sends any request.
     *
     * @param body        request body, or {@code null}
     * @param contentType content type of the body, or {@code null}
     * @param headers     further headers as name/value pairs
     */
    public Response send(String method, String path, String body, String contentType, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String json(Object body) {
        return body instanceof String text ? text : JsonCodec.shared().toJson(body);
    }
}
