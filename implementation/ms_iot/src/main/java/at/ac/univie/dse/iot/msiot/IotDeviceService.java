package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.cf.CommunicationFramework;
import at.ac.univie.dse.iot.cf.CommunicationFrameworkFactory;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.model.Topics;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.msiot.sensor.SimulatedSensor;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;

/**
 * MS IoT: a simulated IoT device with one sensor that publishes a stream of readings through
 * the CF on topic {@code sensor.<dataType>}.
 *
 * <p>Single Implementation, Multiple Instances: every device runs this class; temperature and
 * CO2 devices only differ in their config file.
 */
public final class IotDeviceService extends AbstractMicroService<IotConfig> {

    private final Function<CfConfig, CommunicationFramework> cfFactory;
    private final Clock clock;
    private CommunicationFramework cf;
    private SensorPublisher publisher;

    public IotDeviceService() {
        this(CommunicationFrameworkFactory::create, Clock.systemUTC());
    }

    /** For tests: a custom CF and clock. */
    IotDeviceService(Function<CfConfig, CommunicationFramework> cfFactory, Clock clock) {
        super(IotConfig.class);
        this.cfFactory = cfFactory;
        this.clock = clock;
    }

    @Override
    protected void initialize(IotConfig config) {
        SensorConfig sensorConfig = config.sensor();
        SimulatedSensor sensor = new SimulatedSensor(config.serviceId(), sensorConfig.dataType(), sensorConfig.unit(),
                ValueGeneratorFactory.create(sensorConfig), ValueGeneratorFactory.rangeOf(sensorConfig.generator()), clock);
        cf = cfFactory.apply(config.cf());
        String topic = Topics.sensor(sensorConfig.dataType());
        publisher = new SensorPublisher(sensor, cf, topic, Duration.ofMillis(sensorConfig.publishIntervalMs()),
                new NamedThreadFactory(config.serviceId() + "-publisher", true));
        log.info("{} publishes {} in {} on {} every {} ms (bootId {})", config.serviceId(), sensorConfig.dataType(),
                sensorConfig.unit(), topic, sensorConfig.publishIntervalMs(), sensor.bootId());
    }

    @Override
    protected void startComponents() {
        cf.start();
        publisher.start();
    }

    @Override
    protected void stopComponents() {
        if (publisher != null) {
            publisher.stop();
        }
        if (cf != null) {
            cf.close();
        }
    }

    /** Readings published so far, for tests and diagnostics. */
    long publishedCount() {
        return publisher == null ? 0 : publisher.publishedCount();
    }
}
