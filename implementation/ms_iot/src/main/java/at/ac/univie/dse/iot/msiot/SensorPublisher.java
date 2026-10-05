package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.cf.CommunicationFramework;
import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.msiot.sensor.Sensor;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the sensor at a fixed rate and publishes every reading through the CF.
 *
 * <p>Publishing never blocks. Readings taken while the device has no peers are dropped by the
 * CF: a live sensor stream gains nothing from stale readings flooding in later.
 */
public final class SensorPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SensorPublisher.class);

    private final Sensor sensor;
    private final CommunicationFramework cf;
    private final String topic;
    private final Duration interval;
    private final ThreadFactory threadFactory;
    private final AtomicLong published = new AtomicLong();
    private ScheduledExecutorService scheduler;

    public SensorPublisher(Sensor sensor, CommunicationFramework cf, String topic, Duration interval,
            ThreadFactory threadFactory) {
        this.sensor = sensor;
        this.cf = cf;
        this.topic = topic;
        this.interval = interval;
        this.threadFactory = threadFactory;
    }

    public synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("publisher already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduler.scheduleAtFixedRate(this::publishOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        ExecutorUtils.shutdownGracefully(scheduler, Duration.ofSeconds(2));
    }

    /** Number of readings handed to the CF so far. */
    public long publishedCount() {
        return published.get();
    }

    /** Takes and publishes one reading. Never throws, so the schedule keeps running. */
    void publishOnce() {
        try {
            Optional<SensorReading> reading = sensor.read();
            if (reading.isEmpty()) {
                return;
            }
            if (cf.connectedPeers().isEmpty()) {
                LOG.debug("No peers yet, reading {} is not delivered", reading.get().sequence());
            }
            cf.publish(topic, reading.get());
            published.incrementAndGet();
            LOG.debug("Published {} on {}", reading.get(), topic);
        } catch (RuntimeException e) {
            LOG.error("Publishing a reading failed", e);
        }
    }
}
