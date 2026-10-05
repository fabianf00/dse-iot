package at.ac.univie.dse.iot.msml.jobs;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msml.anomaly.AnomalyDetector;
import at.ac.univie.dse.iot.msml.anomaly.BehaviourChain;
import at.ac.univie.dse.iot.msml.anomaly.FuturePhase;
import at.ac.univie.dse.iot.msml.anomaly.Phase;
import at.ac.univie.dse.iot.msml.anomaly.ThresholdCondition;
import at.ac.univie.dse.iot.msml.anomaly.ThresholdCondition.Operator;
import at.ac.univie.dse.iot.msml.data.TimeSeriesStore;
import at.ac.univie.dse.iot.msml.events.EventLog;
import at.ac.univie.dse.iot.msml.events.MlEvent;
import at.ac.univie.dse.iot.msml.prediction.HistogramConfig;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobsTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");

    private final Clock clock = Clock.systemUTC();
    private final JobQueue queue = new JobQueue(100);
    private final TimeSeriesStore store = new TimeSeriesStore(Duration.ofHours(1));
    private final EventLog events = new EventLog(100, clock);
    private final AnomalyDetector detector = new AnomalyDetector(List.of(new BehaviourChain("warm", "warm stays warm",
            new Phase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 4, 2),
            new Phase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 2, 0),
            new FuturePhase(TEMPERATURE, new ThresholdCondition(Operator.GT, 20), 2))), 2, store);
    private final MlJobs jobs = new MlJobs(queue, store, detector,
            new HistogramPredictor(List.of(new HistogramConfig(TEMPERATURE, 1, 0, 10))), events, clock);
    private WorkerPool workers;

    @AfterEach
    void stopWorkers() {
        if (workers != null) {
            workers.stop(Duration.ofSeconds(1));
        }
    }

    private static Job job(String name, Runnable action) {
        return new Job() {
            @Override
            public void execute() {
                action.run();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static AggregatedValue temperature(double value) {
        return new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 1, 1, 1, value);
    }

    private void startWorkers(int threads) {
        workers = new WorkerPool(queue, threads, new NamedThreadFactory("test-worker", true));
        workers.start();
    }

    @Test
    void fullQueueDropsJobsInsteadOfBlocking() {
        JobQueue small = new JobQueue(2);

        assertTrue(small.submit(job("a", () -> { })));
        assertTrue(small.submit(job("b", () -> { })));
        assertFalse(small.submit(job("c", () -> { })));
        assertEquals(1, small.droppedCount());
        assertEquals(2, small.size());
    }

    @Test
    void workersExecuteJobsAndSurviveFailingOnes() {
        AtomicInteger done = new AtomicInteger();
        startWorkers(3);

        queue.submit(job("fails", () -> {
            throw new IllegalStateException("boom");
        }));
        for (int i = 0; i < 50; i++) {
            queue.submit(job("count", done::incrementAndGet));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> done.get() == 50);
        assertEquals(1, workers.failedCount());
        assertEquals(50, workers.executedCount());
    }

    @Test
    void detectionJobsAreCoalescedPerDataType() {
        jobs.submitDetection(TEMPERATURE);
        jobs.submitDetection(TEMPERATURE);
        jobs.submitDetection(DataType.of("co2"));

        assertEquals(2, queue.size(), "one pending detection per data type");
    }

    @Test
    void expectationChecksAreCoalesced() {
        assertTrue(jobs.submitExpectationCheck());
        assertFalse(jobs.submitExpectationCheck());
        assertEquals(1, queue.size());
    }

    @Test
    void emptyAggregatesAreNotProcessed() {
        assertFalse(jobs.submitIngest(AggregatedValue.empty(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 1),
                Instant.now()));
        assertEquals(0, queue.size());
    }

    @Test
    void ingestedValuesFlowIntoStorePredictionAndDetection() {
        startWorkers(4);
        Instant start = Instant.now();

        for (int i = 0; i < 10; i++) {
            jobs.submitIngest(temperature(22), start.minusMillis(5_000 - i * 500L));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> store.range(TEMPERATURE, start.minusSeconds(10), start).size() == 10);
        await().atMost(Duration.ofSeconds(5)).until(() -> events.lastSequence() >= 1);
        assertInstanceOf(MlEvent.PredictionChangedEvent.class, events.since(0, 10).events().get(0));
        await().atMost(Duration.ofSeconds(5)).until(() -> detector.openExpectation("warm").isPresent());

        jobs.submitIngest(temperature(14), Instant.now());

        await().atMost(Duration.ofSeconds(5)).until(() -> events.since(0, 10).events().stream()
                .anyMatch(e -> e instanceof MlEvent.AnomalyEvent));
    }
}
