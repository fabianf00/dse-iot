package at.ac.univie.dse.iot.msfog;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.cf.CommunicationFramework;
import at.ac.univie.dse.iot.cf.CommunicationFrameworkFactory;
import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.model.Topics;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.msfog.aggregation.AggregationScheduler;
import at.ac.univie.dse.iot.msfog.aggregation.AggregationStrategies;
import at.ac.univie.dse.iot.msfog.aggregation.SensorReadingCollector;
import at.ac.univie.dse.iot.msfog.udp.AggregatePublisher;
import at.ac.univie.dse.iot.msfog.udp.FogRequestHandler;
import at.ac.univie.dse.iot.msfog.udp.SubscriptionManager;
import at.ac.univie.dse.iot.msfog.udp.UdpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * MS Fog: receives the readings of one data type through the CF, aggregates them per window
 * and pushes each aggregate to the subscribers of its UDP Fog Aggregate API.
 *
 * <p>Single Implementation, Multiple Instances: one instance per data type, configured by file.
 */
public final class FogNodeService extends AbstractMicroService<FogConfig> {

    private static final long LEASE_SWEEP_INTERVAL_MS = 1_000;

    private final Function<CfConfig, CommunicationFramework> cfFactory;
    private final Clock clock;
    private CommunicationFramework cf;
    private SensorReadingCollector collector;
    private AggregationScheduler aggregation;
    private SubscriptionManager subscriptions;
    private UdpServer udp;
    private AggregatePublisher publisher;
    private ScheduledExecutorService leaseSweeper;

    public FogNodeService() {
        this(CommunicationFrameworkFactory::create, Clock.systemUTC());
    }

    /** For tests: a custom CF and clock. */
    FogNodeService(Function<CfConfig, CommunicationFramework> cfFactory, Clock clock) {
        super(FogConfig.class);
        this.cfFactory = cfFactory;
        this.clock = clock;
    }

    @Override
    protected void initialize(FogConfig config) {
        FogMessageCodec codec = new FogMessageCodec();
        subscriptions = new SubscriptionManager(config.udp().maxLeaseMs(), config.udp().maxSubscribers(), clock::millis);
        long fogEpoch = clock.millis();
        FogRequestHandler handler = new FogRequestHandler(codec, subscriptions, new FogRequestHandler.StreamDescription(
                config.dataType(), config.unit(), config.aggregationMode(), config.windowMs(), fogEpoch));
        udp = new UdpServer(new InetSocketAddress(config.udp().bindHost(), config.udp().port()), handler, codec,
                config.serviceId() + "-udp");
        publisher = new AggregatePublisher(config.serviceId(), fogEpoch, subscriptions, codec, udp::send);

        collector = new SensorReadingCollector(config.dataType(), config.unit(), clock::millis);
        aggregation = new AggregationScheduler(collector, AggregationStrategies.forMode(config.aggregationMode()),
                config.dataType(), config.unit(), publisher, config.emptyWindowWarningAfter());

        cf = cfFactory.apply(config.cf());
        cf.subscribe(Topics.sensor(config.dataType()), collector);
        log.info("{} aggregates {} ({}) with {} every {} ms (fogEpoch {})", config.serviceId(), config.dataType(),
                config.unit(), config.aggregationMode(), config.windowMs(), fogEpoch);
    }

    @Override
    protected void startComponents() throws Exception {
        udp.start();
        cf.start();
        aggregation.start(Duration.ofMillis(config().windowMs()),
                new NamedThreadFactory(config().serviceId() + "-aggregation", true));
        leaseSweeper = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory(config().serviceId() + "-leases", true));
        leaseSweeper.scheduleWithFixedDelay(subscriptions::sweepExpired,
                LEASE_SWEEP_INTERVAL_MS, LEASE_SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void stopComponents() {
        if (leaseSweeper != null) {
            ExecutorUtils.shutdownGracefully(leaseSweeper, Duration.ofSeconds(1));
        }
        if (aggregation != null) {
            aggregation.stop();
        }
        if (cf != null) {
            cf.close();
        }
        if (udp != null) {
            udp.close();
        }
    }
}
