package at.ac.univie.dse.iot.msml.rmi;

import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.anomaly.PhaseObservation;
import at.ac.univie.dse.iot.msml.events.MlEvent;
import at.ac.univie.dse.iot.msml.prediction.Prediction;
import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.MlEventDto;
import at.ac.univie.dse.iot.mlapi.PhaseObservationDto;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.PredictionDto;

/** Converts internal events and predictions into the DTOs of the RMI API. */
final class DtoMapper {

    private DtoMapper() {
    }

    static MlEventDto toDto(MlEvent event) {
        if (event instanceof MlEvent.AnomalyEvent anomalyEvent) {
            return toDto(anomalyEvent);
        }
        MlEvent.PredictionChangedEvent change = (MlEvent.PredictionChangedEvent) event;
        return new PredictionChangeDto(change.sequence(), change.occurredAt(), change.dataType().value(),
                change.change().previous() == null ? null : toDto(change.change().previous()),
                toDto(change.change().current()));
    }

    static PredictionDto toDto(Prediction prediction) {
        return new PredictionDto(prediction.dataType().value(), prediction.binLower(), prediction.binUpper(),
                prediction.mostLikelyValue(), prediction.confidence(), prediction.sampleCount(),
                prediction.unit() == null ? null : prediction.unit().name());
    }

    private static AnomalyDto toDto(MlEvent.AnomalyEvent event) {
        Anomaly anomaly = event.anomaly();
        return new AnomalyDto(event.sequence(), event.occurredAt(), event.dataType().value(), anomaly.chain().id(),
                anomaly.chain().description(), toDto(anomaly.expectation().past()),
                toDto(anomaly.expectation().current()), anomaly.expected(), anomaly.observedValue(),
                anomaly.unit() == null ? null : anomaly.unit().name(), anomaly.expectation().deadline());
    }

    private static PhaseObservationDto toDto(PhaseObservation observation) {
        return new PhaseObservationDto(observation.condition(), observation.from(), observation.to(), observation.samples());
    }
}
