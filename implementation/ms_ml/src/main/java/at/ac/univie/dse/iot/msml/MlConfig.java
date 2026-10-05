package at.ac.univie.dse.iot.msml;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ServiceConfig;
import at.ac.univie.dse.iot.msml.anomaly.AnomalyConfig;
import at.ac.univie.dse.iot.msml.events.EventsConfig;
import at.ac.univie.dse.iot.msml.ingest.FogClientConfig;
import at.ac.univie.dse.iot.msml.ingest.FogEndpoint;
import at.ac.univie.dse.iot.msml.jobs.WorkerConfig;
import at.ac.univie.dse.iot.msml.prediction.PredictionConfig;
import at.ac.univie.dse.iot.msml.rest.RestConfig;
import at.ac.univie.dse.iot.msml.rmi.RmiConfig;
import at.ac.univie.dse.iot.msml.storage.StorageConfig;
import java.util.List;

/**
 * Root config of an MS ML instance, e.g. {@code config/ml-1.json}.
 *
 * @param serviceId                  unique id; used as subscriber id at the fogs
 * @param fogNodes                   fogs to subscribe to; their ids must be unique
 * @param udp                        UDP client settings
 * @param workers                    job/worker settings, optional
 * @param timeSeriesRetentionSeconds how long values are kept; at least the longest chain span,
 *                                   default {@value #DEFAULT_TIME_SERIES_RETENTION_SECONDS}
 * @param anomaly                    behaviour chains
 * @param prediction                 histograms
 * @param events                     event log settings, optional
 * @param rmi                        RMI endpoint, optional
 * @param storage                    database of the historic results, optional; by default an H2
 *                                   file under {@code run/data/<serviceId>}
 * @param rest                       REST API, optional; by default port 50303 for the WEB ML origins
 */
public record MlConfig(
        String serviceId,
        List<FogEndpoint> fogNodes,
        FogClientConfig udp,
        WorkerConfig workers,
        long timeSeriesRetentionSeconds,
        AnomalyConfig anomaly,
        PredictionConfig prediction,
        EventsConfig events,
        RmiConfig rmi,
        StorageConfig storage,
        RestConfig rest) implements ServiceConfig {

    public static final long DEFAULT_TIME_SERIES_RETENTION_SECONDS = 3_600;

    public MlConfig {
        fogNodes = fogNodes == null ? null : List.copyOf(fogNodes);
        workers = workers == null ? new WorkerConfig(0, 0) : workers;
        timeSeriesRetentionSeconds = timeSeriesRetentionSeconds == 0
                ? DEFAULT_TIME_SERIES_RETENTION_SECONDS : timeSeriesRetentionSeconds;
        events = events == null ? new EventsConfig(0) : events;
        rmi = rmi == null ? new RmiConfig(null, 0, 0, null) : rmi;
        storage = storage == null ? StorageConfig.defaultFor(serviceId) : storage.withDefaultUrl(serviceId);
        rest = rest == null ? RestConfig.defaults() : rest;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("serviceId", serviceId)
                .notEmpty("fogNodes", fogNodes)
                .each("fogNodes", fogNodes);
        if (fogNodes != null) {
            validator.check(fogNodes.stream().map(FogEndpoint::id).distinct().count() == fogNodes.size(),
                    "fogNodes", "must have unique ids");
        }
        validator.nested("udp", udp)
                .nested("workers", workers)
                .nested("anomaly", anomaly)
                .nested("prediction", prediction)
                .nested("events", events)
                .nested("rmi", rmi)
                .nested("storage", storage)
                .nested("rest", rest);
        if (anomaly != null && anomaly.chains() != null && !validator.hasProblems()) {
            validator.check(timeSeriesRetentionSeconds >= anomaly.maxSpanSeconds(), "timeSeriesRetentionSeconds",
                    "must cover the longest behaviour chain (" + anomaly.maxSpanSeconds() + " s)");
        }
    }
}
