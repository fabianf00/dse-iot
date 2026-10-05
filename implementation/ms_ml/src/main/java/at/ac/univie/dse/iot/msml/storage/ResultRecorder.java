package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.msml.events.MlEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes every event of the {@code EventLog} into the database, on its own thread.
 *
 * <p>Registered as an event log listener: {@link #accept} only queues the event, so the workers
 * that produce events never wait for the database, and recording is never dropped by the job
 * queue's backpressure. A full queue or a database error is logged and counted, not thrown.
 * {@code recordedAt} is stamped when the event is written.
 */
public final class ResultRecorder implements Consumer<MlEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(ResultRecorder.class);
    private static final long POLL_MS = 100;

    private final String mlInstance;
    private final long serverEpoch;
    private final ResultRepository repository;
    private final Clock clock;
    private final ThreadFactory threadFactory;
    private final BlockingQueue<MlEvent> queue;
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running;
    private Thread thread;

    /**
     * @param mlInstance  service id of this ML instance
     * @param serverEpoch epoch of the event log the events come from
     */
    public ResultRecorder(String mlInstance, long serverEpoch, ResultRepository repository, int queueCapacity,
            Clock clock, ThreadFactory threadFactory) {
        this.mlInstance = mlInstance;
        this.serverEpoch = serverEpoch;
        this.repository = repository;
        this.clock = clock;
        this.threadFactory = threadFactory;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    /** Queues an event for recording; never blocks. */
    @Override
    public void accept(MlEvent event) {
        if (!queue.offer(event)) {
            long count = failed.incrementAndGet();
            LOG.warn("Recorder queue full, event {} is not stored ({} not stored so far)", event.sequence(), count);
        }
    }

    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("recorder already started");
        }
        running = true;
        thread = threadFactory.newThread(this::recordLoop);
        thread.start();
    }

    /** Writes the queued events (up to {@code timeout}), then stops. */
    public synchronized void stop(Duration timeout) {
        running = false;
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeout.toMillis());
            if (thread.isAlive()) {
                LOG.warn("Recorder did not finish within {}, {} events not stored", timeout, queue.size());
                thread.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thread.interrupt();
        }
        LOG.info("Recorder stopped: {} recorded, {} duplicates, {} failed", recorded.get(), duplicates.get(), failed.get());
    }

    public long recordedCount() {
        return recorded.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    /** Events that could not be stored: queue full or database error. */
    public long failedCount() {
        return failed.get();
    }

    public int queueSize() {
        return queue.size();
    }

    private void recordLoop() {
        try {
            while (running || !queue.isEmpty()) {
                MlEvent event = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    write(event);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void write(MlEvent event) {
        try {
            if (repository.record(toEntity(event))) {
                recorded.incrementAndGet();
            } else {
                duplicates.incrementAndGet();
            }
        } catch (RuntimeException e) {
            long count = failed.incrementAndGet();
            LOG.error("Storing event {} failed ({} not stored so far): {}", event.sequence(), count, e.getMessage());
        }
    }

    private MlResultEntity toEntity(MlEvent event) {
        if (event instanceof MlEvent.AnomalyEvent anomaly) {
            return new AnomalyEntity(mlInstance, serverEpoch, anomaly, clock.instant());
        }
        return new PredictionEntity(mlInstance, serverEpoch, (MlEvent.PredictionChangedEvent) event, clock.instant());
    }
}
