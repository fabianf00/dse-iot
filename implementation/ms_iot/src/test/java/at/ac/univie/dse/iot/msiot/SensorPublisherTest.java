package at.ac.univie.dse.iot.msiot;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.CommunicationFramework;
import at.ac.univie.dse.iot.cf.MessageListener;
import at.ac.univie.dse.iot.cf.NodeId;
import at.ac.univie.dse.iot.cf.PeerInfo;
import at.ac.univie.dse.iot.cf.Subscription;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SensorPublisherTest {

    /** CF stand-in that records what is published. */
    static final class RecordingCf implements CommunicationFramework {
        record Published(String topic, Object payload) {
        }

        final List<Published> published = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void start() {
        }

        @Override
        public void publish(String topic, Object payload) {
            if (failing) {
                throw new IllegalStateException("CF not running");
            }
            published.add(new Published(topic, payload));
        }

        @Override
        public Subscription subscribe(String topicPattern, MessageListener listener) {
            return () -> { };
        }

        @Override
        public NodeId nodeId() {
            return NodeId.of("recording");
        }

        @Override
        public Set<PeerInfo> connectedPeers() {
            return Set.of();
        }

        @Override
        public void close() {
        }
    }

    private final RecordingCf cf = new RecordingCf();
    private final AtomicLong sequence = new AtomicLong();
    private final UUID bootId = UUID.randomUUID();

    private Optional<SensorReading> nextReading() {
        return Optional.of(new SensorReading("iot-temp-1", DataType.of("temperature"), Unit.CELSIUS, 21.5,
                sequence.getAndIncrement(), bootId, 0));
    }

    private SensorPublisher publisher(at.ac.univie.dse.iot.msiot.sensor.Sensor sensor, Duration interval) {
        return new SensorPublisher(sensor, cf, "sensor.temperature", interval, new NamedThreadFactory("test-publisher", true));
    }

    @Test
    void publishOncePublishesTheReadingOnTheTopic() {
        SensorPublisher publisher = publisher(this::nextReading, Duration.ofSeconds(1));

        publisher.publishOnce();

        assertEquals(1, cf.published.size());
        assertEquals("sensor.temperature", cf.published.get(0).topic());
        assertEquals(0, ((SensorReading) cf.published.get(0).payload()).sequence());
        assertEquals(1, publisher.publishedCount());
    }

    @Test
    void emptyReadingIsNotPublished() {
        SensorPublisher publisher = publisher(Optional::empty, Duration.ofSeconds(1));

        publisher.publishOnce();

        assertTrue(cf.published.isEmpty());
    }

    @Test
    void failingPublishDoesNotStopTheSchedule() {
        SensorPublisher publisher = publisher(this::nextReading, Duration.ofMillis(20));
        cf.failing = true;
        publisher.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> sequence.get() >= 3);
            cf.failing = false;
            await().atMost(Duration.ofSeconds(5)).until(() -> cf.published.size() >= 3);
        } finally {
            publisher.stop();
        }
    }

    @Test
    void stopEndsPublishing() throws InterruptedException {
        SensorPublisher publisher = publisher(this::nextReading, Duration.ofMillis(10));
        publisher.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> cf.published.size() >= 2);

        publisher.stop();
        int afterStop = cf.published.size();
        Thread.sleep(100);

        assertEquals(afterStop, cf.published.size());
    }
}
