package at.ac.univie.dse.iot.msml.rmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import at.ac.univie.dse.iot.msml.events.EventLog;
import at.ac.univie.dse.iot.msml.prediction.HistogramConfig;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.mlapi.PollResult;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.PredictionDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MlRemoteServiceImplTest {

    private static final Instant NOW = Instant.parse("2021-11-20T14:03:05Z");
    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final DataType CO2 = DataType.of("co2");

    private final EventLog events = new EventLog(1_000, Clock.fixed(NOW, ZoneOffset.UTC));
    private final HistogramPredictor predictor = new HistogramPredictor(List.of(new HistogramConfig(CO2, 50, 0, 10)));
    private final MlRemoteServiceImpl service = new MlRemoteServiceImpl(events, predictor, () -> null);

    private static Anomaly anomaly() {
        BehaviourChain chain = new BehaviourChain("temp-sudden-drop", "warm stays warm",
                new Phase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 120, 60),
                new Phase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 60, 0),
                new FuturePhase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 60));
        PhaseObservation past = new PhaseObservation("temperature > 20.0", NOW.minusSeconds(180), NOW.minusSeconds(120), 6);
        PhaseObservation current = new PhaseObservation("temperature > 20.0", NOW.minusSeconds(120), NOW.minusSeconds(60), 6);
        return new Anomaly(new Expectation(chain, NOW.minusSeconds(60), NOW, past, current), 14.2, NOW, Unit.CELSIUS);
    }

    @Test
    void eventsAreMappedToDtos() {
        events.append(anomaly());
        predictor.update(CO2, Unit.PPM, 610).ifPresent(events::append);

        PollResult result = service.pollEvents(0, 10);

        assertEquals(events.epoch(), result.serverEpoch());
        assertEquals(2, result.nextCursor());
        AnomalyDto anomaly = assertInstanceOf(AnomalyDto.class, result.events().get(0));
        assertEquals(1, anomaly.sequence());
        assertEquals("temperature", anomaly.dataType());
        assertEquals("temp-sudden-drop", anomaly.chainId());
        assertEquals("temperature > 20.0", anomaly.expected());
        assertEquals(14.2, anomaly.observedValue());
        assertEquals("CELSIUS", anomaly.unit());
        assertEquals(6, anomaly.past().samples());
        assertEquals(NOW, anomaly.expectedUntil());
        PredictionChangeDto change = assertInstanceOf(PredictionChangeDto.class, result.events().get(1));
        assertNull(change.previous());
        assertEquals(new PredictionDto("co2", 600, 650, 625, 1.0, 1, "PPM"), change.current());
    }

    @Test
    void currentPredictionsAreListed() {
        predictor.update(CO2, Unit.PPM, 610);

        assertEquals(List.of(new PredictionDto("co2", 600, 650, 625, 1.0, 1, "PPM")), service.getCurrentPredictions());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.pollEvents(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.pollEvents(0, 0));
    }

    @Test
    void largeRequestsAreCapped() {
        for (int i = 0; i < MlRemoteService.MAX_EVENTS_PER_POLL + 10; i++) {
            events.append(anomaly());
        }

        PollResult result = service.pollEvents(0, 10_000);

        assertEquals(MlRemoteService.MAX_EVENTS_PER_POLL, result.events().size());
        assertEquals(true, result.hasMore());
    }
}
