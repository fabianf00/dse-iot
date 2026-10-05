package at.ac.univie.dse.iot.msfog.udp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessage.ErrorCode;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.fogapi.FogProtocolException;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Subscription leases, request handling and aggregate push, without sockets. */
class UdpApiTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final InetSocketAddress SOURCE = new InetSocketAddress("127.0.0.1", 40_000);
    private static final InetSocketAddress CALLBACK = new InetSocketAddress("127.0.0.1", 50_301);

    private final AtomicLong now = new AtomicLong(10_000);
    private final SubscriptionManager subscriptions = new SubscriptionManager(3_000, 2, now::get);
    private final FogMessageCodec codec = new FogMessageCodec();
    private final FogRequestHandler handler = new FogRequestHandler(codec, subscriptions,
            new FogRequestHandler.StreamDescription(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 1_000, 777));

    private FogMessage request(FogMessage message) {
        byte[] data = codec.encode(message);
        return handler.handle(data, data.length, SOURCE);
    }

    private FogMessage raw(String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        return handler.handle(data, data.length, SOURCE);
    }

    @Test
    void subscribeIsAcknowledgedWithTheStreamDescriptionAndCappedLease() {
        FogMessage.Subscribe subscribe = FogMessage.Subscribe.of("ml-1", null, 50_301, 60_000);

        FogMessage.SubscribeAck ack = assertInstanceOf(FogMessage.SubscribeAck.class, request(subscribe));

        assertEquals(subscribe.requestId(), ack.requestId());
        assertEquals(TEMPERATURE, ack.dataType());
        assertEquals(Unit.CELSIUS, ack.unit());
        assertEquals(AggregationMode.AVG, ack.aggregationMode());
        assertEquals(1_000, ack.windowMs());
        assertEquals(777, ack.fogEpoch());
        assertEquals(13_000, ack.leaseExpiresAt(), "lease capped at 3 s");
        assertEquals(CALLBACK, subscriptions.active().get(0).callback(), "callback host defaults to the source");
    }

    @Test
    void explicitCallbackHostIsUsed() {
        request(FogMessage.Subscribe.of("ml-1", "10.8.0.4", 50_301, 1_000));

        assertEquals(new InetSocketAddress("10.8.0.4", 50_301), subscriptions.active().get(0).callback());
    }

    @Test
    void repeatedSubscribeKeepsTheSubscriptionId() {
        FogMessage.SubscribeAck first = (FogMessage.SubscribeAck) request(FogMessage.Subscribe.of("ml-1", null, 50_301, 1_000));
        now.addAndGet(500);
        FogMessage.SubscribeAck second = (FogMessage.SubscribeAck) request(FogMessage.Subscribe.of("ml-1", null, 50_302, 1_000));

        assertEquals(first.subscriptionId(), second.subscriptionId());
        assertEquals(1, subscriptions.active().size());
        assertEquals(50_302, subscriptions.active().get(0).callback().getPort());
        assertEquals(11_500, second.leaseExpiresAt());
    }

    @Test
    void renewExtendsTheLease() {
        FogMessage.SubscribeAck ack = (FogMessage.SubscribeAck) request(FogMessage.Subscribe.of("ml-1", null, 50_301, 1_000));
        now.addAndGet(900);

        FogMessage.RenewAck renewed = assertInstanceOf(FogMessage.RenewAck.class,
                request(FogMessage.Renew.of(ack.subscriptionId(), 1_000)));

        assertEquals(11_900, renewed.leaseExpiresAt());
        now.addAndGet(500);
        assertEquals(1, subscriptions.active().size());
    }

    @Test
    void expiredSubscriptionStopsReceivingAndCannotBeRenewed() {
        FogMessage.SubscribeAck ack = (FogMessage.SubscribeAck) request(FogMessage.Subscribe.of("ml-1", null, 50_301, 1_000));
        now.addAndGet(1_000);

        assertTrue(subscriptions.active().isEmpty());
        FogMessage.Error error = assertInstanceOf(FogMessage.Error.class,
                request(FogMessage.Renew.of(ack.subscriptionId(), 1_000)));
        assertEquals(ErrorCode.UNKNOWN_SUBSCRIPTION, error.code());
    }

    @Test
    void unsubscribeRemovesTheSubscription() {
        FogMessage.SubscribeAck ack = (FogMessage.SubscribeAck) request(FogMessage.Subscribe.of("ml-1", null, 50_301, 1_000));

        assertInstanceOf(FogMessage.UnsubscribeAck.class, request(FogMessage.Unsubscribe.of(ack.subscriptionId())));
        assertTrue(subscriptions.active().isEmpty());
        assertEquals(ErrorCode.UNKNOWN_SUBSCRIPTION,
                ((FogMessage.Error) request(FogMessage.Unsubscribe.of(ack.subscriptionId()))).code());
    }

    @Test
    void subscriberLimitIsEnforcedButExpiredLeasesFreeSlots() {
        request(FogMessage.Subscribe.of("a", null, 1, 1_000));
        request(FogMessage.Subscribe.of("b", null, 2, 1_000));

        FogMessage.Error error = assertInstanceOf(FogMessage.Error.class, request(FogMessage.Subscribe.of("c", null, 3, 1_000)));
        assertEquals(ErrorCode.TOO_MANY_SUBSCRIBERS, error.code());

        now.addAndGet(1_000);
        assertInstanceOf(FogMessage.SubscribeAck.class, request(FogMessage.Subscribe.of("c", null, 3, 1_000)));
    }

    @Test
    void sweepRemovesExpiredSubscriptionsAndFreesTheirSlots() throws Exception {
        subscriptions.subscribe("a", CALLBACK, 1_000);
        subscriptions.subscribe("b", CALLBACK, 1_000);
        now.addAndGet(1_000);

        subscriptions.sweepExpired();

        assertTrue(subscriptions.active().isEmpty());
        subscriptions.subscribe("c", CALLBACK, 1_000);
        subscriptions.subscribe("d", CALLBACK, 1_000);
        assertThrows(SubscriptionManager.TooManySubscribersException.class,
                () -> subscriptions.subscribe("e", CALLBACK, 1_000));
    }

    @Test
    void invalidRequestsGetErrors() {
        assertEquals(ErrorCode.MALFORMED_REQUEST, ((FogMessage.Error) raw("{\"garbage\":")).code());
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, ((FogMessage.Error) raw("{\"v\":2,\"type\":\"SUBSCRIBE\"}")).code());
        FogMessage.Error notARequest = (FogMessage.Error) request(FogMessage.Aggregate.of("other-fog", 1, 1,
                AggregatedValue.empty(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 1)));
        assertEquals(ErrorCode.MALFORMED_REQUEST, notARequest.code());
        assertTrue(subscriptions.active().isEmpty());
    }

    @Test
    void publisherSendsNumberedAggregatesToEveryActiveSubscriber() throws Exception {
        record Sent(byte[] datagram, InetSocketAddress target) {
        }
        List<Sent> sent = new ArrayList<>();
        AggregatePublisher publisher = new AggregatePublisher("fog-temp", 777, subscriptions, codec,
                (datagram, target) -> sent.add(new Sent(datagram, target)));
        subscriptions.subscribe("a", new InetSocketAddress("127.0.0.1", 1), 1_000);
        subscriptions.subscribe("b", new InetSocketAddress("127.0.0.1", 2), 1_000);
        AggregatedValue value = new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 1_000, 2, 1, 21.0);

        publisher.accept(value);
        publisher.accept(value);

        assertEquals(4, sent.size());
        FogMessage.Aggregate first = (FogMessage.Aggregate) decode(sent.get(0).datagram());
        FogMessage.Aggregate third = (FogMessage.Aggregate) decode(sent.get(2).datagram());
        assertEquals(1, first.sequence());
        assertEquals(2, third.sequence());
        assertEquals("fog-temp", first.fogId());
        assertEquals(777, first.fogEpoch());
        assertEquals(value, first.toAggregatedValue());
        assertEquals(List.of(1, 2, 1, 2), sent.stream().map(s -> s.target().getPort()).toList());
    }

    private FogMessage decode(byte[] datagram) throws FogProtocolException {
        return codec.decode(datagram);
    }
}
