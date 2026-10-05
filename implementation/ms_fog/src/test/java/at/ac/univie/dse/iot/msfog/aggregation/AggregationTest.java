package at.ac.univie.dse.iot.msfog.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.CfMessage;
import at.ac.univie.dse.iot.cf.MessageType;
import at.ac.univie.dse.iot.cf.NodeId;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AggregationTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final UUID BOOT = UUID.randomUUID();

    private final AtomicLong now = new AtomicLong(1_000);
    private final SensorReadingCollector collector = new SensorReadingCollector(TEMPERATURE, Unit.CELSIUS, now::get);
    private final List<AggregatedValue> published = new ArrayList<>();

    private static SensorReading reading(String device, double value) {
        return new SensorReading(device, TEMPERATURE, Unit.CELSIUS, value, 0, BOOT, 0);
    }

    private static CfMessage message(Object payload) {
        return new CfMessage(1, UUID.randomUUID(), MessageType.DATA, NodeId.of("iot"), NodeId.of("iot"), 1, 0,
                "sensor.temperature", JsonCodec.shared().toTree(payload));
    }

    private AggregationScheduler scheduler(AggregationMode mode) {
        return new AggregationScheduler(collector, AggregationStrategies.forMode(mode), TEMPERATURE, Unit.CELSIUS,
                published::add, 3);
    }

    @Test
    void strategiesAggregateValues() {
        List<Double> values = List.of(5.0, 7.0, 3.0);

        assertEquals(OptionalDouble.of(5.0), AggregationStrategies.forMode(AggregationMode.AVG).aggregate(values));
        assertEquals(OptionalDouble.of(7.0), AggregationStrategies.forMode(AggregationMode.MAX).aggregate(values));
        assertEquals(OptionalDouble.of(3.0), AggregationStrategies.forMode(AggregationMode.MIN).aggregate(values));
        for (AggregationMode mode : AggregationMode.values()) {
            assertEquals(OptionalDouble.empty(), AggregationStrategies.forMode(mode).aggregate(List.of()));
            assertEquals(mode, AggregationStrategies.forMode(mode).mode());
        }
    }

    @Test
    void windowAggregateCountsSamplesAndDevices() {
        AggregationScheduler scheduler = scheduler(AggregationMode.AVG);
        collector.onMessage(message(reading("iot-temp-1", 10)));
        collector.onMessage(message(reading("iot-temp-2", 20)));
        collector.onMessage(message(reading("iot-temp-1", 30)));
        now.set(11_000);

        AggregatedValue value = scheduler.aggregateAndPublish();

        assertEquals(new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 1_000, 11_000, 3, 2, 20.0), value);
        assertEquals(List.of(value), published);
    }

    @Test
    void emptyWindowIsPublishedWithoutValue() {
        AggregationScheduler scheduler = scheduler(AggregationMode.MAX);
        now.set(2_000);

        AggregatedValue value = scheduler.aggregateAndPublish();

        assertTrue(value.isEmpty());
        assertEquals(1_000, value.windowStart());
        assertEquals(2_000, value.windowEnd());
        assertEquals(1, published.size());
    }

    @Test
    void consecutiveWindowsDoNotShareReadings() {
        AggregationScheduler scheduler = scheduler(AggregationMode.MAX);
        collector.add(reading("a", 1));
        now.set(2_000);
        AggregatedValue first = scheduler.aggregateAndPublish();
        collector.add(reading("a", 5));
        now.set(3_000);
        AggregatedValue second = scheduler.aggregateAndPublish();

        assertEquals(1.0, first.value());
        assertEquals(5.0, second.value());
        assertEquals(1, second.sampleCount());
        assertEquals(2_000, second.windowStart());
    }

    @Test
    void readingsOfOtherTypesUnitsOrInvalidPayloadsAreRejected() {
        collector.onMessage(message(new SensorReading("iot-co2-1", DataType.of("co2"), Unit.PPM, 900, 0, BOOT, 0)));
        collector.onMessage(message(new SensorReading("iot-x", TEMPERATURE, Unit.PERCENT, 50, 0, BOOT, 0)));
        collector.onMessage(message(JsonNodeFactory.instance.objectNode().put("not", "a reading")));
        collector.onMessage(message(reading("iot-temp-1", 21)));

        assertEquals(3, collector.rejectedCount());
        assertEquals(1, collector.acceptedCount());
        assertEquals(1, collector.closeWindow().values().size());
    }

    @Test
    void noReadingIsLostOrCountedTwiceWhileWindowsAreClosedConcurrently() throws Exception {
        int writers = 4;
        int readingsPerWriter = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            String device = "device-" + w;
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < readingsPerWriter; i++) {
                    collector.add(reading(device, 1));
                }
                return null;
            }));
        }

        long counted = 0;
        int windows = 0;
        start.countDown();
        while (!tasks.stream().allMatch(Future::isDone)) {
            counted += collector.closeWindow().values().size();
            windows++;
        }
        counted += collector.closeWindow().values().size();
        for (Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(windows > 10, "windows were closed while writers were adding, got " + windows);
        assertEquals((long) writers * readingsPerWriter, counted);
        assertEquals((long) writers * readingsPerWriter, collector.acceptedCount());
    }
}
