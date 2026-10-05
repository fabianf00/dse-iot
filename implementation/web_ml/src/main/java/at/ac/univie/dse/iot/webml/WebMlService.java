package at.ac.univie.dse.iot.webml;

import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.webml.StaticFiles.StaticFile;
import at.ac.univie.dse.iot.webml.WebMlConfig.Endpoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * WEB ML: serves the static UI and {@code /config.json} (the MS ML REST endpoints for the
 * browser). It holds no data and no business logic; the browser talks to MS ML directly.
 */
public final class WebMlService extends AbstractMicroService<WebMlConfig> {

    static final String CONFIG_PATH = "/config.json";
    private static final int THREADS = 4;

    /** Body of {@code /config.json}. */
    record BrowserConfig(List<Endpoint> endpoints, long refreshIntervalMs) {
    }

    private final StaticFiles files = new StaticFiles();
    private HttpServer server;
    private ExecutorService executor;
    private byte[] browserConfig;
    private String contentSecurityPolicy;

    public WebMlService() {
        super(WebMlConfig.class);
    }

    @Override
    protected void initialize(WebMlConfig config) throws IOException {
        browserConfig = JsonCodec.shared().toBytes(new BrowserConfig(config.endpoints(), config.refreshIntervalMs()));
        String apiOrigins = config.endpoints().stream().map(Endpoint::origin).distinct().collect(Collectors.joining(" "));
        // Scripts and styles only from this server; API calls only to the configured MS ML instances.
        contentSecurityPolicy = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                + "connect-src 'self' " + apiOrigins + "; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
        server = HttpServer.create(new InetSocketAddress(config.bindHost(), config.port()), 0);
        executor = Executors.newFixedThreadPool(THREADS, new NamedThreadFactory(config.serviceId() + "-http", true));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    @Override
    protected void startComponents() {
        server.start();
        log.info("{} serves the UI at http://{}:{}/ for {}", config().serviceId(), config().bindHost(), port(),
                config().endpoints().stream().map(e -> e.name() + " (" + e.restUrl() + ")").toList());
    }

    @Override
    protected void stopComponents() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            ExecutorUtils.shutdownGracefully(executor, Duration.ofSeconds(1));
        }
    }

    /** The bound port. */
    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = method.equals("HEAD");
            if (!head && !method.equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Optional<StaticFile> file = CONFIG_PATH.equals(path)
                    ? Optional.of(new StaticFile(browserConfig, "application/json"))
                    : files.find(path);
            if (file.isEmpty()) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found\n".getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            send(exchange, 200, file.get().contentType(), file.get().content(), head);
        } catch (RuntimeException e) {
            log.warn("{} {} failed", exchange.getRequestMethod(), exchange.getRequestURI(), e);
        }
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body, boolean head) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", contentSecurityPolicy);
        if (head) {
            headers.set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
