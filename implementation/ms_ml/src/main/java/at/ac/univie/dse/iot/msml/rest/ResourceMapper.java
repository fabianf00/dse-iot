package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.msml.prediction.Prediction;
import at.ac.univie.dse.iot.msml.rest.Resources.AnomalyResource;
import at.ac.univie.dse.iot.msml.rest.Resources.CommentResource;
import at.ac.univie.dse.iot.msml.rest.Resources.CurrentPredictionResource;
import at.ac.univie.dse.iot.msml.rest.Resources.PhaseResource;
import at.ac.univie.dse.iot.msml.rest.Resources.PredictionResource;
import at.ac.univie.dse.iot.msml.storage.StoredResult;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredAnomaly;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredPhase;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredPrediction;

/** Maps stored results and predictions to their JSON representations. */
final class ResourceMapper {

    private ResourceMapper() {
    }

    static Object toResource(StoredResult result) {
        if (result instanceof StoredAnomaly anomaly) {
            return new AnomalyResource(anomaly.id(), anomaly.kind().name(), anomaly.mlInstance(), anomaly.serverEpoch(),
                    anomaly.eventSequence(), anomaly.dataType(), anomaly.occurredAt(), anomaly.recordedAt(),
                    anomaly.chainId(), anomaly.chainDescription(), anomaly.expected(), anomaly.observedValue(),
                    anomaly.unit(), anomaly.observedAt(), anomaly.expectedUntil(), toResource(anomaly.past()),
                    toResource(anomaly.current()), anomaly.comments().stream().map(ResourceMapper::toResource).toList());
        }
        StoredPrediction prediction = (StoredPrediction) result;
        return new PredictionResource(prediction.id(), prediction.kind().name(), prediction.mlInstance(),
                prediction.serverEpoch(), prediction.eventSequence(), prediction.dataType(), prediction.occurredAt(),
                prediction.recordedAt(), prediction.unit(), prediction.binLower(), prediction.binUpper(),
                prediction.mostLikelyValue(), prediction.confidence(), prediction.sampleCount(),
                prediction.previousBinLower(), prediction.previousBinUpper(),
                prediction.comments().stream().map(ResourceMapper::toResource).toList());
    }

    static CommentResource toResource(StoredComment comment) {
        return new CommentResource(comment.id(), comment.resultId(), comment.text(), comment.author(),
                comment.createdAt(), comment.updatedAt());
    }

    static CurrentPredictionResource toResource(Prediction prediction) {
        return new CurrentPredictionResource(prediction.dataType().value(),
                prediction.unit() == null ? null : prediction.unit().name(), prediction.binLower(),
                prediction.binUpper(), prediction.mostLikelyValue(), prediction.confidence(), prediction.sampleCount());
    }

    private static PhaseResource toResource(StoredPhase phase) {
        return phase == null ? null : new PhaseResource(phase.condition(), phase.from(), phase.to(), phase.samples());
    }
}
