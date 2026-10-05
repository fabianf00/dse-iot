package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.anomaly.BehaviourChain;
import at.ac.univie.dse.iot.msml.anomaly.Expectation;
import at.ac.univie.dse.iot.msml.anomaly.FuturePhase;
import at.ac.univie.dse.iot.msml.anomaly.Phase;
import at.ac.univie.dse.iot.msml.anomaly.PhaseObservation;
import at.ac.univie.dse.iot.msml.anomaly.ThresholdCondition;
import at.ac.univie.dse.iot.msml.anomaly.ThresholdCondition.Operator;
import at.ac.univie.dse.iot.msml.events.MlEvent;
import at.ac.univie.dse.iot.msml.prediction.Prediction;
import at.ac.univie.dse.iot.msml.prediction.PredictionChange;
import java.time.Instant;
import java.util.UUID;

/** Events and databases for storage tests. */
final class StorageFixtures {

    static final Instant T0 = Instant.parse("2021-11-20T14:00:00Z");
    static final long EPOCH = 1637412000000L;

    private StorageFixtures() {
    }

    /** A fresh in-memory database. */
    static Database memoryDatabase() {
        return Database.open(new StorageConfig("jdbc:h2:mem:storage-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                null, null, 0, 0));
    }

    static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    static MlEvent.AnomalyEvent anomaly(long sequence, String dataType, String chainId, long second, double observed) {
        DataType type = DataType.of(dataType);
        ThresholdCondition condition = new ThresholdCondition(Operator.GT, 20);
        BehaviourChain chain = new BehaviourChain(chainId, chainId + " description",
                new Phase(type, condition, 120, 60), new Phase(type, condition, 60, 0), new FuturePhase(type, condition, 60));
        Expectation expectation = new Expectation(chain, at(second - 10), at(second + 50),
                new PhaseObservation(dataType + " > 20.0", at(second - 130), at(second - 70), 6),
                new PhaseObservation(dataType + " > 20.0", at(second - 70), at(second - 10), 7));
        return new MlEvent.AnomalyEvent(sequence, at(second),
                new Anomaly(expectation, observed, at(second - 1), Unit.CELSIUS));
    }

    static MlEvent.PredictionChangedEvent prediction(long sequence, String dataType, long second, double lower) {
        DataType type = DataType.of(dataType);
        Prediction previous = new Prediction(type, Unit.PPM, lower - 50, lower, lower - 25, 0.4, 20);
        Prediction current = new Prediction(type, Unit.PPM, lower, lower + 50, lower + 25, 0.5, 21);
        return new MlEvent.PredictionChangedEvent(sequence, at(second), new PredictionChange(type, previous, current));
    }

    static AnomalyEntity anomalyEntity(MlEvent.AnomalyEvent event) {
        return new AnomalyEntity("ml-1", EPOCH, event, event.occurredAt().plusMillis(100));
    }

    static PredictionEntity predictionEntity(MlEvent.PredictionChangedEvent event) {
        return new PredictionEntity("ml-1", EPOCH, event, event.occurredAt().plusMillis(100));
    }
}
