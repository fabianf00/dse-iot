package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import at.ac.univie.dse.iot.msml.rest.Resources.MetaResource;
import at.ac.univie.dse.iot.msml.rest.Resources.StatusResource;
import io.javalin.http.Context;
import java.util.function.Supplier;

/** Live data that is not stored: current predictions, status and filter metadata. */
final class LiveController {

    private final HistogramPredictor predictor;
    private final Supplier<StatusResource> status;
    private final Supplier<MetaResource> meta;

    LiveController(HistogramPredictor predictor, Supplier<StatusResource> status, Supplier<MetaResource> meta) {
        this.predictor = predictor;
        this.status = status;
        this.meta = meta;
    }

    /** {@code GET /predictions/current} */
    void currentPredictions(Context ctx) {
        ctx.json(predictor.currentAll().stream().map(ResourceMapper::toResource).toList());
    }

    /** {@code GET /status} */
    void status(Context ctx) {
        ctx.json(status.get());
    }

    /** {@code GET /meta} */
    void meta(Context ctx) {
        ctx.json(meta.get());
    }
}
