package at.ac.univie.dse.iot.msml.ingest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.testsupport.FakeFogServer;
import at.ac.univie.dse.iot.testsupport.Ports;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FogClientTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final InetSocketAddress FOG_ADDRESS = new InetSocketAddress("127.0.0.1", 1);

    private final List<Double> received = new CopyOnWriteArrayList<>();
    private FakeFogServer fog;
    private FogClient client;

    @AfterEach
    void close() {
        if (client != null) {
            client.close();
        }
        if (fog != null) {
            fog.close();
        }
    }

    private FogClient client(int fogPort, long leaseMs) {
        return new FogClient("ml-test", List.of(new FogEndpoint("fog-temp", "127.0.0.1", fogPort)),
                new FogClientConfig("127.0.0.1", Ports.freeUdpPort(), null, leaseMs, 2_000),
                (fogId, aggregate, receivedAt) -> received.add(aggregate.value()), Clock.systemUTC());
    }

    private static FogMessage.Aggregate aggregate(long epoch, long sequence, double value) {
        return FogMessage.Aggregate.of("fog-temp", epoch, sequence,
                new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 1, 1, 1, value));
    }

    @Test
    void subscribeIsRepeatedUntilAcknowledged() throws Exception {
        fog = new FakeFogServer("fog-temp", TEMPERATURE, Unit.CELSIUS);
        fog.ignoreRequests(true);
        client = client(fog.port(), 30_000);
        client.start();

        await().atMost(TIMEOUT).until(() -> fog.requests().size() >= 2);
        assertEquals(SubscriptionStatus.PENDING, client.status("fog-temp"));
        fog.ignoreRequests(false);

        await().atMost(TIMEOUT).until(() -> client.status("fog-temp") == SubscriptionStatus.ACTIVE);
        assertTrue(fog.requests().stream().allMatch(r -> r instanceof FogMessage.Subscribe));
        assertTrue(fog.hasSubscriber("ml-test"));
    }

    @Test
    void aggregatesArriveAndLeaseIsRenewed() throws Exception {
        fog = new FakeFogServer("fog-temp", TEMPERATURE, Unit.CELSIUS);
        client = client(fog.port(), 2_000);
        client.start();
        await().atMost(TIMEOUT).until(() -> fog.hasSubscriber("ml-test"));

        fog.sendAggregate(21.0);
        fog.sendAggregate(22.0);

        await().atMost(TIMEOUT).until(() -> received.equals(List.of(21.0, 22.0)));
        await().atMost(TIMEOUT).until(() -> fog.requests().stream().anyMatch(r -> r instanceof FogMessage.Renew));
        assertTrue(client.statusSnapshot().get("fog-temp").startsWith("ACTIVE (2 aggregates"));
    }

    @Test
    void unknownSubscriptionAfterFogRestartLeadsToANewSubscription() throws Exception {
        fog = new FakeFogServer("fog-temp", TEMPERATURE, Unit.CELSIUS);
        client = client(fog.port(), 2_000);
        client.start();
        await().atMost(TIMEOUT).until(() -> fog.hasSubscriber("ml-test"));

        fog.restart();

        await().atMost(TIMEOUT).until(() -> fog.hasSubscriber("ml-test"));
        fog.sendAggregate(23.0);
        await().atMost(TIMEOUT).until(() -> received.contains(23.0));
    }

    @Test
    void duplicatesLateDatagramsAndOldEpochsAreDropped() throws Exception {
        client = client(1, 30_000);

        client.handle(aggregate(100, 1, 1.0), FOG_ADDRESS);
        client.handle(aggregate(100, 2, 2.0), FOG_ADDRESS);
        client.handle(aggregate(100, 2, 2.0), FOG_ADDRESS);
        client.handle(aggregate(100, 1, 1.0), FOG_ADDRESS);
        client.handle(aggregate(100, 5, 5.0), FOG_ADDRESS);
        client.handle(aggregate(200, 1, 10.0), FOG_ADDRESS);
        client.handle(aggregate(100, 6, 6.0), FOG_ADDRESS);
        client.handle(aggregate(200, 2, 11.0), FOG_ADDRESS);

        assertEquals(List.of(1.0, 2.0, 5.0, 10.0, 11.0), received,
                "duplicate, reordered and old-epoch aggregates are dropped; a gap is accepted");
    }

    @Test
    void aggregatesOfUnknownFogsAreIgnored() throws Exception {
        client = client(1, 30_000);

        client.handle(FogMessage.Aggregate.of("fog-other", 1, 1,
                new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 1, 1, 1, 9.0)),
                new InetSocketAddress("127.0.0.1", 2));

        assertTrue(received.isEmpty());
    }
}
