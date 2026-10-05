package at.ac.univie.dse.iot.msml;

import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.msml.anomaly.AnomalyDetector;
import at.ac.univie.dse.iot.msml.data.TimeSeriesStore;
import at.ac.univie.dse.iot.msml.events.EventLog;
import at.ac.univie.dse.iot.msml.ingest.FogClient;
import at.ac.univie.dse.iot.msml.jobs.JobQueue;
import at.ac.univie.dse.iot.msml.jobs.MlJobs;
import at.ac.univie.dse.iot.msml.jobs.WorkerPool;
import at.ac.univie.dse.iot.msml.prediction.HistogramPredictor;
import at.ac.univie.dse.iot.msml.rmi.MlRemoteServiceImpl;
import at.ac.univie.dse.iot.msml.rmi.MlRmiServer;
import at.ac.univie.dse.iot.msml.storage.Database;
import at.ac.univie.dse.iot.msml.storage.JpaResultRepository;
import at.ac.univie.dse.iot.msml.storage.ResultRecorder;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MS ML: subscribes to all fog nodes over UDP, detects anomalies with behaviour chains and
 * predicts values with histograms, all in jobs executed by a worker pool, and offers the results
 * through the Java RMI ML Service API.
 *
 * <p>MS ML does not use the CF: it talks to the fogs over UDP and to clients over RMI.
 */
public final class MlService extends AbstractMicroService<MlConfig> {

    private static final long EXPECTATION_CHECK_INTERVAL_MS = 1_000;

    private final Clock clock;
    private Instant startedAt;
    private TimeSeriesStore store;
    private EventLog events;
    private HistogramPredictor predictor;
    private JobQueue queue;
    private WorkerPool workers;
    private MlJobs jobs;
    private FogClient fogClient;
    private MlRmiServer rmiServer;
    private ScheduledExecutorService timer;
    private Database database;
    private ResultRecorder recorder;

    public MlService() {
        this(Clock.systemUTC());
    }

    /** For tests: a custom clock. */
    MlService(Clock clock) {
        super(MlConfig.class);
        this.clock = clock;
    }

    @Override
    protected void initialize(MlConfig config) {
        startedAt = clock.instant();
        store = new TimeSeriesStore(Duration.ofSeconds(config.timeSeriesRetentionSeconds()));
        events = new EventLog(config.events().retention(), clock);
        database = Database.open(config.storage());
        recorder = new ResultRecorder(config.serviceId(), events.epoch(), new JpaResultRepository(database),
                config.storage().recorderQueueCapacity(), clock,
                new NamedThreadFactory(config.serviceId() + "-recorder", true));
        events.addListener(recorder);
        predictor = new HistogramPredictor(config.prediction().histograms());
        AnomalyDetector detector = new AnomalyDetector(config.anomaly().chains(),
                config.anomaly().minSamplesPerPhase(), store);
        queue = new JobQueue(config.workers().queueCapacity());
        workers = new WorkerPool(queue, config.workers().threads(),
                new NamedThreadFactory(config.serviceId() + "-worker", true));
        jobs = new MlJobs(queue, store, detector, predictor, events, clock);
        fogClient = new FogClient(config.serviceId(), config.fogNodes(), config.udp(),
                (fogId, aggregate, receivedAt) -> jobs.submitIngest(aggregate, receivedAt), clock);
        rmiServer = new MlRmiServer(config.rmi(),
                new MlRemoteServiceImpl(events, predictor, this::status));
        log.info("{} subscribes to {} fogs, {} behaviour chains, {} histograms, {} workers (epoch {}), stores results in {}",
                config.serviceId(), config.fogNodes().size(), config.anomaly().chains().size(),
                config.prediction().histograms().size(), config.workers().threads(), events.epoch(),
                database.jdbcUrl());
    }

    @Override
    protected void startComponents() throws Exception {
        recorder.start();
        workers.start();
        timer = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory(config().serviceId() + "-timer", true));
        timer.scheduleWithFixedDelay(jobs::submitExpectationCheck,
                EXPECTATION_CHECK_INTERVAL_MS, EXPECTATION_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        fogClient.start();
        rmiServer.start();
    }

    @Override
    protected void stopComponents() {
        if (rmiServer != null) {
            rmiServer.stop();
        }
        if (fogClient != null) {
            fogClient.close();
        }
        if (timer != null) {
            ExecutorUtils.shutdownGracefully(timer, Duration.ofSeconds(1));
        }
        if (workers != null) {
            workers.stop(Duration.ofSeconds(2));
        }
        if (recorder != null) {
            recorder.stop(Duration.ofSeconds(5));
        }
        if (database != null) {
            database.close();
        }
    }

    ServiceStatusDto status() {
        return new ServiceStatusDto(config().serviceId(), events.epoch(), startedAt,
                store.dataTypes().stream().map(DataType::value).sorted().toList(), fogClient.statusSnapshot(),
                queue.size(), queue.droppedCount(), events.lastSequence());
    }
}
