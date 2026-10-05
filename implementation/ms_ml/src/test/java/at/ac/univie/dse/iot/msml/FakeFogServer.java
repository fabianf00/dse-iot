package at.ac.univie.dse.iot.msml;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.fogapi.FogProtocolException;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A scriptable fog node speaking the Fog Aggregate API over real UDP, for tests of MS ML.
 */
public final class FakeFogServer implements AutoCloseable {

    private record Subscriber(String subscriptionId, InetSocketAddress callback) {
    }

    private final String fogId;
    private final DataType dataType;
    private final Unit unit;
    private final FogMessageCodec codec = new FogMessageCodec();
    private final DatagramSocket socket;
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final List<FogMessage> requests = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile long epoch = System.currentTimeMillis();
    private volatile boolean ignoreRequests;
    private volatile InetSocketAddress lastCallback;

    public FakeFogServer(String fogId, DataType dataType, Unit unit) throws IOException {
        this.fogId = fogId;
        this.dataType = dataType;
        this.unit = unit;
        this.socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        Thread receiver = new Thread(this::receive, fogId + "-fake");
        receiver.setDaemon(true);
        receiver.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public long epoch() {
        return epoch;
    }

    /** While set, requests are recorded but not answered (lost answers). */
    public void ignoreRequests(boolean ignore) {
        this.ignoreRequests = ignore;
    }

    public List<FogMessage> requests() {
        return requests;
    }

    public boolean hasSubscriber(String subscriberId) {
        return subscribers.containsKey(subscriberId);
    }

    /** Sends the next aggregate with {@code value} to all subscribers. */
    public void sendAggregate(double value) {
        long now = System.currentTimeMillis();
        send(FogMessage.Aggregate.of(fogId, epoch, sequence.incrementAndGet(),
                new AggregatedValue(dataType, unit, AggregationMode.AVG, now - 1_000, now, 3, 3, value)));
    }

    /** Sends an aggregate with explicit epoch and sequence to the last known callback, e.g. a replay. */
    public void sendRaw(long epoch, long sequence, double value) {
        long now = System.currentTimeMillis();
        sendTo(FogMessage.Aggregate.of(fogId, epoch, sequence,
                new AggregatedValue(dataType, unit, AggregationMode.AVG, now - 1_000, now, 3, 3, value)), lastCallback);
    }

    /** Simulates a restart: new epoch, sequence starts again, subscriptions are forgotten. */
    public void restart() {
        epoch = Math.max(System.currentTimeMillis(), epoch + 1);
        sequence.set(0);
        subscribers.clear();
    }

    private void send(FogMessage message) {
        subscribers.values().forEach(subscriber -> sendTo(message, subscriber.callback()));
    }

    private void sendTo(FogMessage message, InetSocketAddress target) {
        byte[] datagram = codec.encode(message);
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, target));
        } catch (IOException e) {
            if (!socket.isClosed()) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void receive() {
        byte[] buffer = new byte[65_535];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                FogMessage request = codec.decode(packet.getData(), 0, packet.getLength());
                requests.add(request);
                if (!ignoreRequests) {
                    sendTo(answer(request, (InetSocketAddress) packet.getSocketAddress()),
                            (InetSocketAddress) packet.getSocketAddress());
                }
            } catch (IOException | FogProtocolException e) {
                // Closed, or not a valid request.
            }
        }
    }

    private FogMessage answer(FogMessage request, InetSocketAddress source) {
        long now = System.currentTimeMillis();
        if (request instanceof FogMessage.Subscribe subscribe) {
            InetSocketAddress callback = new InetSocketAddress(source.getAddress(), subscribe.callbackPort());
            lastCallback = callback;
            Subscriber subscriber = subscribers.computeIfAbsent(subscribe.subscriberId(),
                    id -> new Subscriber(UUID.randomUUID().toString(), callback));
            return new FogMessage.SubscribeAck(1, subscribe.requestId(), subscriber.subscriptionId(), dataType, unit,
                    AggregationMode.AVG, 1_000, now + subscribe.leaseMs(), epoch);
        }
        if (request instanceof FogMessage.Renew renew) {
            boolean known = subscribers.values().stream().anyMatch(s -> s.subscriptionId().equals(renew.subscriptionId()));
            return known ? new FogMessage.RenewAck(1, renew.requestId(), renew.subscriptionId(), now + renew.leaseMs())
                    : FogMessage.Error.of(renew.requestId(), FogMessage.ErrorCode.UNKNOWN_SUBSCRIPTION, "unknown");
        }
        if (request instanceof FogMessage.Unsubscribe unsubscribe) {
            subscribers.values().removeIf(s -> s.subscriptionId().equals(unsubscribe.subscriptionId()));
            return new FogMessage.UnsubscribeAck(1, unsubscribe.requestId(), unsubscribe.subscriptionId());
        }
        return FogMessage.Error.of(null, FogMessage.ErrorCode.MALFORMED_REQUEST, "unexpected");
    }

    @Override
    public void close() {
        socket.close();
    }
}
