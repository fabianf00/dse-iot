package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import at.ac.univie.dse.iot.msml.rest.Resources.MetaResource;
import at.ac.univie.dse.iot.msml.rest.Resources.ProblemDetails;
import at.ac.univie.dse.iot.msml.rest.Resources.StatusResource;
import at.ac.univie.dse.iot.msml.results.InvalidRequestException;
import at.ac.univie.dse.iot.msml.results.ResultService;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import at.ac.univie.dse.iot.msml.storage.StorageException;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ML REST API v1 (Javalin on Jetty): stored anomalies and predictions (load, filter, delete,
 * comment) and live data (current predictions, status, metadata).
 *
 * <p>All errors are answered with RFC 9457 problem details ({@code application/problem+json}).
 * Browsers on the configured origins may call the API (CORS).
 */
public final class RestServer {

    /** Base path of all endpoints. */
    public static final String BASE_PATH = "/api/v1";

    private static final Logger LOG = LoggerFactory.getLogger(RestServer.class);
    private static final String PROBLEM_JSON = "application/problem+json";

    private final RestConfig config;
    private final ResultsController anomalies;
    private final ResultsController predictions;
    private final CommentsController comments;
    private final LiveController live;
    private Javalin app;

    public RestServer(RestConfig config, ResultService results, HistogramPredictor predictor,
            Supplier<StatusResource> status, Supplier<MetaResource> meta) {
        this.config = config;
        this.anomalies = new ResultsController(ResultKind.ANOMALY, results);
        this.predictions = new ResultsController(ResultKind.PREDICTION, results);
        this.comments = new CommentsController(results);
        this.live = new LiveController(predictor, status, meta);
    }

    public synchronized void start() {
        app = Javalin.create(this::configure).start(config.host(), config.port());
        LOG.info("ML REST API available at http://{}:{}{}", config.host(), app.port(), BASE_PATH);
    }

    /** The bound port (useful when configured with port 0). */
    public synchronized int port() {
        return app.port();
    }

    public synchronized void stop() {
        if (app != null) {
            app.stop();
            app = null;
        }
    }

    private void configure(JavalinConfig javalin) {
        javalin.startup.showJavalinBanner = false;
        javalin.startup.showOldJavalinVersionWarning = false;
        javalin.http.prefer405over404 = true;
        javalin.jsonMapper(new JavalinJackson(JsonCodec.shared().copyMapper(), false));
        List<String> origins = config.allowedOrigins();
        if (!origins.isEmpty()) {
            javalin.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.allowHost(origins.get(0), origins.subList(1, origins.size()).toArray(String[]::new));
                rule.exposeHeader("Location");
            }));
        }

        var routes = javalin.routes;
        // Fixed paths before {id} paths, so "current" is not taken for an id.
        routes.get(BASE_PATH + "/predictions/current", live::currentPredictions);
        routes.get(BASE_PATH + "/status", live::status);
        routes.get(BASE_PATH + "/meta", live::meta);

        routes.get(BASE_PATH + "/anomalies", anomalies::list);
        routes.delete(BASE_PATH + "/anomalies", anomalies::deleteMatching);
        routes.get(BASE_PATH + "/anomalies/{id}", anomalies::get);
        routes.delete(BASE_PATH + "/anomalies/{id}", anomalies::delete);
        routes.get(BASE_PATH + "/anomalies/{id}/comments", ctx -> comments.list(ResultKind.ANOMALY, ctx));
        routes.post(BASE_PATH + "/anomalies/{id}/comments", ctx -> comments.add(ResultKind.ANOMALY, ctx));

        routes.get(BASE_PATH + "/predictions", predictions::list);
        routes.delete(BASE_PATH + "/predictions", predictions::deleteMatching);
        routes.get(BASE_PATH + "/predictions/{id}", predictions::get);
        routes.delete(BASE_PATH + "/predictions/{id}", predictions::delete);
        routes.get(BASE_PATH + "/predictions/{id}/comments", ctx -> comments.list(ResultKind.PREDICTION, ctx));
        routes.post(BASE_PATH + "/predictions/{id}/comments", ctx -> comments.add(ResultKind.PREDICTION, ctx));

        routes.put(BASE_PATH + "/comments/{id}", comments::update);
        routes.delete(BASE_PATH + "/comments/{id}", comments::delete);

        routes.exception(ApiException.class, (e, ctx) -> problem(ctx, e.status(), e.getMessage()));
        routes.exception(InvalidRequestException.class, (e, ctx) -> problem(ctx, 400, e.getMessage()));
        routes.exception(StorageException.class, (e, ctx) -> {
            LOG.warn("{} {} failed: {}", ctx.method(), ctx.path(), e.getMessage());
            problem(ctx, 503, "the result database is not available");
        });
        routes.exception(Exception.class, (e, ctx) -> {
            LOG.error("{} {} failed", ctx.method(), ctx.path(), e);
            problem(ctx, 500, "internal error");
        });
        routes.error(404, ctx -> problemIfMissing(ctx, "no endpoint " + ctx.method() + " " + ctx.path()));
        routes.error(405, ctx -> problemIfMissing(ctx, ctx.method() + " is not allowed on " + ctx.path()));
    }

    private static void problem(Context ctx, int status, String detail) {
        HttpStatus httpStatus = HttpStatus.forStatus(status);
        ProblemDetails problem = new ProblemDetails("about:blank", httpStatus.getMessage(), status, detail);
        ctx.status(status).contentType(PROBLEM_JSON).result(JsonCodec.shared().toJson(problem));
    }

    /** Error statuses set by Javalin itself (unknown route, wrong method) get a problem body too. */
    private static void problemIfMissing(Context ctx, String detail) {
        String contentType = ctx.res().getContentType();
        if (contentType == null || !contentType.startsWith(PROBLEM_JSON)) {
            problem(ctx, ctx.status().getCode(), detail);
        }
    }
}
