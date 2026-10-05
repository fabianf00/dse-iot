package at.ac.univie.dse.iot.msml.events;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.prediction.PredictionChange;
import java.time.Instant;

/** An entry of the {@link EventLog}: what clients see when they poll. */
public sealed interface MlEvent {

    /** Position in the log, strictly increasing, starting at 1. */
    long sequence();

    Instant occurredAt();

    DataType dataType();

    /** A behaviour chain was broken. */
    record AnomalyEvent(long sequence, Instant occurredAt, Anomaly anomaly) implements MlEvent {
        @Override
        public DataType dataType() {
            return anomaly.chain().future().dataType();
        }
    }

    /** The predicted bin of a data type changed. */
    record PredictionChangedEvent(long sequence, Instant occurredAt, PredictionChange change) implements MlEvent {
        @Override
        public DataType dataType() {
            return change.dataType();
        }
    }
}
