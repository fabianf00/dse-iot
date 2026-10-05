package at.ac.univie.dse.iot.msfog;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessage.ErrorCode;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Topics;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.testsupport.CfTestPeer;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.UdpFogTestClient;
import at.ac.univie.dse.iot.testsupport.Waiting;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IT-Fog: window aggregation over real CF links and the UDP subscription lifecycle.
 */
class FogNodeIT {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final long WINDOW_MS = 1_000;
    private static final UUID BOOT = UUID.randomUUID();

    private int fogCfPort;
    private int publisherPort;
    private int fogUdpPort;
    private CfTestPeer publisher;
    private FogNodeService fog;
    private UdpFogTestClient client;
    private UdpFogTestClient shortLeaseClient;

    @BeforeEach
    void setUp() {
        fogCfPort = Ports.freeTcpRange(2);
        publisherPort = fogCfPort + 1;
        fogUdpPort = Ports.freeUdpPort();
        publisher = CfTestPeer.passive("test-publisher", publisherPort);
        fog = startFog(AggregationMode.AVG);
        client = new UdpFogTestClient(new InetSocketAddress(Ports.HOST, fogUdpPort));
        shortLeaseClient = new UdpFogTestClient(new InetSocketAddress(Ports.HOST, fogUdpPort));
    }

    @AfterEach
    void tearDown() {
        client.close();
        shortLeaseClient.close();
        fog.stop();
        publisher.close();
    }

    @Test
    void aggregatesWindowsAndManagesSubscriptions() {
        // 1. Subscribe; the lease is capped at maxLeaseMs.
        long before = System.currentTimeMillis();
        FogMessage.SubscribeAck ack = client.request(
                FogMessage.Subscribe.of("t1", null, client.port(), 600_000), FogMessage.SubscribeAck.class);
        assertEquals(TEMPERATURE, ack.dataType());
        assertEquals(Unit.CELSIUS, ack.unit());
        assertEquals(AggregationMode.AVG, ack.aggregationMode());
        assertEquals(WINDOW_MS, ack.windowMs());
        assertTrue(ack.leaseExpiresAt() <= System.currentTimeMillis() + 60_000
                && ack.leaseExpiresAt() >= before + 60_000 - 1_000, "lease capped at 60 s");
        long firstEpoch = ack.fogEpoch();

        // 2. Readings of one window are averaged; CO2 readings are ignored.
        awaitFogConnected();
        client.clearAggregates();
        client.nextAggregate();
        publishTemperature("iot-temp-1", 10);
        publishTemperature("iot-temp-2", 20);
        publishTemperature("iot-temp-3", 30);
        publisher.publish(Topics.sensor(DataType.of("co2")), reading("iot-co2-1", DataType.of("co2"), Unit.PPM, 900));
        publisher.publish(Topics.sensor(TEMPERATURE), reading("iot-co2-1", DataType.of("co2"), Unit.PPM, 900));
        FogMessage.Aggregate averaged = client.nextAggregate();
        assertEquals(3, averaged.sampleCount());
        assertEquals(3, averaged.deviceCount());
        assertEquals(20.0, averaged.value());
        assertEquals("fog-test", averaged.fogId());
        assertEquals(firstEpoch, averaged.fogEpoch());

        // 3. A window without readings still produces an aggregate, without value.
        FogMessage.Aggregate empty = client.nextAggregate();
        assertEquals(0, empty.sampleCount());
        assertNull(empty.value());
        assertTrue(empty.sequence() > averaged.sequence());

        // 4. Many readings across several windows: none lost, none counted twice.
        client.clearAggregates();
        for (int i = 0; i < 1_000; i++) {
            publishTemperature("iot-temp-" + (i % 3), 20);
            if (i % 10 == 0) {
                Waiting.pause(Duration.ofMillis(35));
            }
        }
        int total = 0;
        int nonEmptyWindows = 0;
        FogMessage.Aggregate aggregate;
        int windows = 0;
        do {
            aggregate = client.nextAggregate();
            total += aggregate.sampleCount();
            nonEmptyWindows += aggregate.sampleCount() > 0 ? 1 : 0;
            windows++;
        } while ((total < 1_000 || aggregate.sampleCount() > 0) && windows < 20);
        assertEquals(1_000, total);
        assertTrue(nonEmptyWindows >= 3, "readings spread over " + nonEmptyWindows + " windows");

        // 6. A subscriber that does not renew stops receiving after its lease, the other keeps receiving.
        shortLeaseClient.request(FogMessage.Subscribe.of("t2", null, shortLeaseClient.port(), 1_500),
                FogMessage.SubscribeAck.class);
        shortLeaseClient.nextAggregate();
        Waiting.pause(Duration.ofMillis(2_500));
        shortLeaseClient.clearAggregates();
        client.clearAggregates();
        Waiting.pause(Duration.ofMillis(2 * WINDOW_MS + 200));
        assertNull(shortLeaseClient.pollAggregate(Duration.ZERO), "expired subscriber still receives aggregates");
        assertNotNull(client.pollAggregate(Duration.ZERO), "active subscriber stopped receiving");

        // 7. Malformed and wrong-version requests are answered with errors, and nothing breaks.
        client.sendRaw("{\"garbage\":".getBytes(StandardCharsets.UTF_8));
        assertEquals(ErrorCode.MALFORMED_REQUEST, assertInstanceOf(FogMessage.Error.class, client.nextAnswer()).code());
        client.sendRaw("{\"v\":2,\"type\":\"SUBSCRIBE\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, assertInstanceOf(FogMessage.Error.class, client.nextAnswer()).code());

        // 5. After a restart with MAX, the old subscription is unknown, resubscribing works,
        //    and the new fog reports a new epoch and restarts its sequence.
        fog.stop();
        fog = startFog(AggregationMode.MAX);
        FogMessage.Error unknown = client.request(FogMessage.Renew.of(ack.subscriptionId(), 30_000), FogMessage.Error.class);
        assertEquals(ErrorCode.UNKNOWN_SUBSCRIPTION, unknown.code());
        FogMessage.SubscribeAck again = client.request(
                FogMessage.Subscribe.of("t1", null, client.port(), 30_000), FogMessage.SubscribeAck.class);
        assertNotEquals(firstEpoch, again.fogEpoch());
        assertEquals(AggregationMode.MAX, again.aggregationMode());
        awaitFogConnected();
        client.clearAggregates();
        client.nextAggregate();
        publishTemperature("iot-temp-1", 5);
        publishTemperature("iot-temp-1", 7);
        publishTemperature("iot-temp-1", 3);
        FogMessage.Aggregate max = client.nextAggregate();
        assertEquals(7.0, max.value());
        assertEquals(AggregationMode.MAX, max.aggregationMode());
        assertEquals(again.fogEpoch(), max.fogEpoch());
        assertTrue(max.sequence() < 10, "sequence restarted, was " + max.sequence());
    }

    // ---- helpers ----

    private FogNodeService startFog(AggregationMode mode) {
        CfConfig cf = CfConfig.builder("fog-test", fogCfPort)
                .listenHost(Ports.HOST)
                .scan(CfTestPeer.scanConfig(fogCfPort, publisherPort))
                .build();
        FogConfig config = new FogConfig("fog-test", TEMPERATURE, Unit.CELSIUS, mode, WINDOW_MS, 0,
                new FogConfig.UdpConfig(Ports.HOST, fogUdpPort, 60_000, 0), cf);
        FogNodeService service = new FogNodeService();
        service.start(config);
        return service;
    }

    private void awaitFogConnected() {
        waiting().until(() -> publisher.isConnectedTo("fog-test"));
    }

    private void publishTemperature(String device, double value) {
        publisher.publish(Topics.sensor(TEMPERATURE), reading(device, TEMPERATURE, Unit.CELSIUS, value));
    }

    private static SensorReading reading(String device, DataType type, Unit unit, double value) {
        return new SensorReading(device, type, unit, value, 0, BOOT, System.currentTimeMillis());
    }
}
