package at.ac.univie.dse.iot.msml.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** JSON representations of the REST API (ML REST API v1). */
public final class Resources {

    private Resources() {
    }

    /** A page of a list: {@code total} counts all matching items, not only this page. */
    public record PageResource<T>(List<T> items, long total, int limit, int offset) {
    }

    /** A stored anomaly. */
    public record AnomalyResource(long id, String kind, String mlInstance, long serverEpoch, long eventSequence,
            String dataType, Instant occurredAt, Instant recordedAt, String chainId, String chainDescription,
            String expected, double observedValue, String unit, Instant observedAt, Instant expectedUntil,
            PhaseResource past, PhaseResource current, List<CommentResource> comments) {
    }

    /** A behaviour chain phase that held. */
    public record PhaseResource(String condition, Instant from, Instant to, int samples) {
    }

    /** A stored prediction change. {@code previousBin*} are null for the first prediction of a data type. */
    public record PredictionResource(long id, String kind, String mlInstance, long serverEpoch, long eventSequence,
            String dataType, Instant occurredAt, Instant recordedAt, String unit, double binLower, double binUpper,
            double mostLikelyValue, double confidence, int sampleCount, Double previousBinLower,
            Double previousBinUpper, List<CommentResource> comments) {
    }

    /** A comment on a stored result. */
    public record CommentResource(long id, long resultId, String text, String author, Instant createdAt,
            Instant updatedAt) {
    }

    /** Body of POST …/comments and PUT /comments/{id}. {@code author} is optional and ignored on PUT. */
    public record CommentRequest(String text, String author) {
    }

    /** Answer of a bulk delete. */
    public record DeletedResource(long deleted) {
    }

    /** The live prediction of a data type (not stored). */
    public record CurrentPredictionResource(String dataType, String unit, double binLower, double binUpper,
            double mostLikelyValue, double confidence, int sampleCount) {
    }

    /** Live status: the RMI status plus the state of the result storage. */
    public record StatusResource(String serviceId, long serverEpoch, Instant startedAt, List<String> dataTypes,
            Map<String, String> fogStatus, int queueSize, long droppedJobs, long lastEventSequence,
            StorageStatusResource storage) {
    }

    /** State of the result storage. */
    public record StorageStatusResource(long storedAnomalies, long storedPredictions, long recorded, long failedWrites,
            int queued) {
    }

    /** What can be filtered by: data types and behaviour chains. */
    public record MetaResource(List<String> dataTypes, List<ChainResource> chains) {
    }

    /** A configured behaviour chain. */
    public record ChainResource(String id, String description, List<String> dataTypes) {
    }

    /** Error body (RFC 9457 problem details), content type {@code application/problem+json}. */
    public record ProblemDetails(String type, String title, int status, String detail) {
    }
}
