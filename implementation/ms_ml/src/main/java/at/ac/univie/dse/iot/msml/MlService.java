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
import at.ac.univie.dse.iot.msml.anomaly.BehaviourChain;
import at.ac.univie.dse.iot.msml.rest.Resources.ChainResource;
import at.ac.univie.dse.iot.msml.rest.Resources.MetaResource;
import at.ac.univie.dse.iot.msml.rest.Resources.StatusResource;
import at.ac.univie.dse.iot.msml.rest.Resources.StorageStatusResource;
import at.ac.univie.dse.iot.msml.rest.RestServer;
import at.ac.univie.dse.iot.msml.results.ResultService;
import at.ac.univie.dse.iot.msml.rmi.MlRmiServer;
import at.ac.univie.dse.iot.msml.storage.ResultFilter;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import at.ac.univie.dse.iot.msml.storage.Database;
import at.ac.univie.dse.iot.msml.storage.JpaResultRepository;
import at.ac.univie.dse.iot.msml.storage.ResultRecorder;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
    private ResultService results;
    private RestServer restServer;
    private List<BehaviourChain> chains;

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
        JpaResultRepository repository = new JpaResultRepository(database);
        recorder = new ResultRecorder(config.serviceId(), events.epoch(), repository,
                config.storage().recorderQueueCapacity(), clock,
                new NamedThreadFactory(config.serviceId() + "-recorder", true));
        events.addListener(recorder);
        results = new ResultService(repository, clock);
        chains = config.anomaly().chains();
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
        restServer = new RestServer(config.rest(), results, predictor, this::restStatus, this::meta);
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
        restServer.start();
    }

    @Override
    protected void stopComponents() {
        if (restServer != null) {
            restServer.stop();
        }
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

    /** Port of the REST API (tests start it on port 0). */
    int restPort() {
        return restServer.port();
    }

    /** The RMI status plus the state of the result storage. */
    StatusResource restStatus() {
        ServiceStatusDto status = status();
        StorageStatusResource storage = new StorageStatusResource(
                results.count(ResultFilter.all(ResultKind.ANOMALY)), results.count(ResultFilter.all(ResultKind.PREDICTION)),
                recorder.recordedCount(), recorder.failedCount(), recorder.queueSize());
        return new StatusResource(status.serviceId(), status.serverEpoch(), status.startedAt(), status.dataTypes(),
                status.fogStatus(), status.queueSize(), status.droppedJobs(), status.lastEventSequence(), storage);
    }

    /** Data types (configured or seen) and behaviour chains, e.g. for filter drop-downs. */
    MetaResource meta() {
        Set<String> dataTypes = new TreeSet<>();
        predictor.dataTypes().forEach(type -> dataTypes.add(type.value()));
        store.dataTypes().forEach(type -> dataTypes.add(type.value()));
        List<ChainResource> chainResources = chains.stream()
                .map(chain -> new ChainResource(chain.id(), chain.description(), Stream.of(chain.past().dataType(),
                        chain.current().dataType(), chain.future().dataType()).map(DataType::value).distinct().toList()))
                .toList();
        chainResources.forEach(chain -> dataTypes.addAll(chain.dataTypes()));
        return new MetaResource(List.copyOf(dataTypes), chainResources);
    }
}
