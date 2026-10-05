package at.ac.univie.dse.iot.msml.anomaly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msml.anomaly.ThresholdCondition.Operator;
import at.ac.univie.dse.iot.msml.data.TimeSeriesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnomalyDetectorTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final DataType CO2 = DataType.of("co2");
    private static final Instant T0 = Instant.parse("2021-11-20T14:00:00Z");

    private static final BehaviourChain WARM_STAYS_WARM = new BehaviourChain("temp-sudden-drop", "warm stays warm",
            new Phase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 120, 60),
            new Phase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 60, 0),
            new FuturePhase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 60));

    private static final BehaviourChain VENTILATION = new BehaviourChain("ventilation-effect", "airing helps",
            new Phase(CO2, new ThresholdCondition(Operator.GT, 1000), 120, 60),
            new Phase(TEMPERATURE, new ThresholdCondition(Operator.LT, 19), 60, 0),
            new FuturePhase(CO2, new ThresholdCondition(Operator.LT, 1000), 120));

    private final TimeSeriesStore store = new TimeSeriesStore(Duration.ofHours(1));
    private final AnomalyDetector detector = new AnomalyDetector(List.of(WARM_STAYS_WARM, VENTILATION), 2, store);

    private void feed(DataType type, Unit unit, double value, long fromSecond, long toSecond) {
        for (long second = fromSecond; second <= toSecond; second += 10) {
            store.append(type, unit, at(second), value);
        }
    }

    private void temperature(double value, long fromSecond, long toSecond) {
        feed(TEMPERATURE, Unit.CELSIUS, value, fromSecond, toSecond);
    }

    private static Instant at(long second) {
        return T0.plusSeconds(second);
    }

    @Test
    void expectationOpensWhenPastAndCurrentHold() {
        temperature(22, 0, 120);

        assertTrue(detector.evaluate(TEMPERATURE, at(120)).isEmpty());

        Expectation expectation = detector.openExpectation("temp-sudden-drop").orElseThrow();
        assertEquals(at(120), expectation.createdAt());
        assertEquals(at(180), expectation.deadline());
        assertEquals("temperature > 20.0", expectation.past().condition());
        assertEquals(7, expectation.past().samples());
        assertEquals(at(0), expectation.past().from());
        assertEquals(at(60), expectation.past().to());
    }

    @Test
    void valueBreakingTheExpectationIsAnAnomaly() {
        temperature(22, 0, 120);
        detector.evaluate(TEMPERATURE, at(120));
        temperature(14.2, 130, 130);

        List<Anomaly> anomalies = detector.evaluate(TEMPERATURE, at(130));

        assertEquals(1, anomalies.size());
        Anomaly anomaly = anomalies.get(0);
        assertEquals("temp-sudden-drop", anomaly.chain().id());
        assertEquals(14.2, anomaly.observedValue());
        assertEquals(at(130), anomaly.observedAt());
        assertEquals(Unit.CELSIUS, anomaly.unit());
        assertEquals("temperature > 20.0", anomaly.expected());
        assertTrue(detector.openExpectation("temp-sudden-drop").isEmpty());
    }

    @Test
    void oneIncidentIsReportedOnce() {
        temperature(22, 0, 120);
        detector.evaluate(TEMPERATURE, at(120));
        temperature(14, 130, 170);

        int anomalies = 0;
        for (long second = 130; second <= 170; second += 10) {
            anomalies += detector.evaluate(TEMPERATURE, at(second)).size();
        }

        assertEquals(1, anomalies, "the low values break the current phase, so no new expectation opens");
    }

    @Test
    void expectationIsFulfilledAndRenewedWhileBehaviourIsNormal() {
        temperature(22, 0, 240);

        int anomalies = 0;
        for (long second = 120; second <= 230; second += 10) {
            anomalies += detector.evaluate(TEMPERATURE, at(second)).size();
        }

        assertEquals(0, anomalies);
        assertEquals(at(180), detector.openExpectation("temp-sudden-drop").orElseThrow().createdAt(),
                "the first expectation was fulfilled at its deadline and a new one opened");
    }

    @Test
    void expectationWithoutFutureDataClosesWithoutAnomaly() {
        temperature(22, 0, 120);
        detector.evaluate(TEMPERATURE, at(120));

        List<Anomaly> anomalies = detector.evaluateAll(at(200));

        assertTrue(anomalies.isEmpty());
        assertTrue(detector.openExpectation("temp-sudden-drop").isEmpty(),
                "no new expectation: the current phase has no values any more");
    }

    @Test
    void phaseWithTooFewSamplesDoesNotHold() {
        temperature(22, 50, 50);
        temperature(22, 70, 120);

        detector.evaluate(TEMPERATURE, at(120));

        assertTrue(detector.openExpectation("temp-sudden-drop").isEmpty());
    }

    @Test
    void phaseWithOneBadValueDoesNotHold() {
        temperature(22, 0, 120);
        temperature(19, 30, 30);

        detector.evaluate(TEMPERATURE, at(120));

        assertTrue(detector.openExpectation("temp-sudden-drop").isEmpty());
    }

    @Test
    void chainsCanSpanDataTypes() {
        feed(CO2, Unit.PPM, 1400, 0, 60);
        temperature(18, 60, 120);
        detector.evaluate(TEMPERATURE, at(120));
        assertTrue(detector.openExpectation("ventilation-effect").isPresent());

        feed(CO2, Unit.PPM, 1300, 130, 130);
        List<Anomaly> anomalies = detector.evaluate(CO2, at(130));

        assertEquals(1, anomalies.size());
        assertEquals("ventilation-effect", anomalies.get(0).chain().id());
        assertEquals(Unit.PPM, anomalies.get(0).unit());
        assertEquals("co2 < 1000.0", anomalies.get(0).expected());
    }

    @Test
    void unrelatedDataTypesDoNotTouchAChain() {
        temperature(22, 0, 120);

        detector.evaluate(DataType.of("humidity"), at(120));

        assertTrue(detector.openExpectation("temp-sudden-drop").isEmpty());
    }
}
