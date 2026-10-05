package at.ac.univie.dse.iot.msml.jobs;

import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.anomaly.AnomalyDetector;
import at.ac.univie.dse.iot.msml.data.TimeSeriesStore;
import at.ac.univie.dse.iot.msml.events.EventLog;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates and submits the ML jobs (Factory):
 *
 * <pre>
 * aggregate received ─► IngestAggregateJob ─► store the value
 *                                           ├► UpdatePredictionJob  (one per value)
 *                                           └► DetectAnomaliesJob   (coalesced per data type)
 * every second ───────► ExpectationTimeoutJob (coalesced)
 * </pre>
 *
 * <p>Detection jobs are coalesced: while one is pending for a data type, no second one is queued,
 * because one run covers all values stored until it starts. Prediction jobs are not coalesced:
 * every value has to enter its histogram.
 */
public final class MlJobs {

    private final JobQueue queue;
    private final TimeSeriesStore store;
    private final AnomalyDetector detector;
    private final HistogramPredictor predictor;
    private final EventLog events;
    private final Clock clock;
    private final Map<DataType, AtomicBoolean> detectionPending = new ConcurrentHashMap<>();
    private final AtomicBoolean timeoutCheckPending = new AtomicBoolean();

    public MlJobs(JobQueue queue, TimeSeriesStore store, AnomalyDetector detector, HistogramPredictor predictor,
            EventLog events, Clock clock) {
        this.queue = queue;
        this.store = store;
        this.detector = detector;
        this.predictor = predictor;
        this.events = events;
        this.clock = clock;
    }

    /** Queues the processing of a received aggregate. Empty aggregates carry no value and are skipped. */
    public boolean submitIngest(AggregatedValue aggregate, Instant receivedAt) {
        if (aggregate.isEmpty()) {
            return false;
        }
        return queue.submit(new IngestAggregateJob(this, aggregate, receivedAt));
    }

    /** Queues a periodic check that closes expectations whose deadline passed. */
    public boolean submitExpectationCheck() {
        if (!timeoutCheckPending.compareAndSet(false, true)) {
            return false;
        }
        if (!queue.submit(new ExpectationTimeoutJob(this))) {
            timeoutCheckPending.set(false);
            return false;
        }
        return true;
    }

    void ingest(AggregatedValue aggregate, Instant receivedAt) {
        store.append(aggregate.dataType(), aggregate.unit(), receivedAt, aggregate.value());
        queue.submit(new UpdatePredictionJob(this, aggregate.dataType(), aggregate.unit(), aggregate.value()));
        submitDetection(aggregate.dataType());
    }

    void submitDetection(DataType dataType) {
        AtomicBoolean pending = detectionPending.computeIfAbsent(dataType, t -> new AtomicBoolean());
        if (pending.compareAndSet(false, true) && !queue.submit(new DetectAnomaliesJob(this, dataType))) {
            pending.set(false);
        }
    }

    void detect(DataType dataType) {
        // Cleared before running, so values stored during this run trigger another run.
        detectionPending.get(dataType).set(false);
        record(detector.evaluate(dataType, clock.instant()));
    }

    void checkExpectations() {
        timeoutCheckPending.set(false);
        record(detector.evaluateAll(clock.instant()));
    }

    void predict(DataType dataType, Unit unit, double value) {
        predictor.update(dataType, unit, value).ifPresent(events::append);
    }

    private void record(List<Anomaly> anomalies) {
        anomalies.forEach(events::append);
    }

    /** Stores a received value, then queues prediction and detection. */
    record IngestAggregateJob(MlJobs jobs, AggregatedValue aggregate, Instant receivedAt) implements Job {
        @Override
        public void execute() {
            jobs.ingest(aggregate, receivedAt);
        }

        @Override
        public String name() {
            return "ingest " + aggregate.dataType();
        }
    }

    /** Evaluates the behaviour chains that involve a data type. */
    record DetectAnomaliesJob(MlJobs jobs, DataType dataType) implements Job {
        @Override
        public void execute() {
            jobs.detect(dataType);
        }

        @Override
        public String name() {
            return "detect anomalies " + dataType;
        }
    }

    /** Adds a value to the histogram of its data type. */
    record UpdatePredictionJob(MlJobs jobs, DataType dataType, Unit unit, double value) implements Job {
        @Override
        public void execute() {
            jobs.predict(dataType, unit, value);
        }

        @Override
        public String name() {
            return "update prediction " + dataType;
        }
    }

    /** Closes expectations whose deadline passed, also when no new data arrives. */
    record ExpectationTimeoutJob(MlJobs jobs) implements Job {
        @Override
        public void execute() {
            jobs.checkExpectations();
        }

        @Override
        public String name() {
            return "check expectations";
        }
    }
}
