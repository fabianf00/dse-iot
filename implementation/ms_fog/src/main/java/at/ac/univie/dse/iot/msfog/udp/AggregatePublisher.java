package at.ac.univie.dse.iot.msfog.udp;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes every aggregate as an AGGREGATE datagram to all active subscribers.
 *
 * <p>{@code sequence} counts aggregates since this fog started ({@code fogEpoch}), so a subscriber
 * can detect lost or reordered datagrams and fog restarts.
 */
public final class AggregatePublisher implements Consumer<AggregatedValue> {

    private static final Logger LOG = LoggerFactory.getLogger(AggregatePublisher.class);

    private final String fogId;
    private final long fogEpoch;
    private final SubscriptionManager subscriptions;
    private final FogMessageCodec codec;
    private final BiConsumer<byte[], InetSocketAddress> sender;
    private final AtomicLong sequence = new AtomicLong();

    /**
     * @param sender sends one datagram, e.g. {@link UdpServer#send}
     */
    public AggregatePublisher(String fogId, long fogEpoch, SubscriptionManager subscriptions, FogMessageCodec codec,
            BiConsumer<byte[], InetSocketAddress> sender) {
        this.fogId = fogId;
        this.fogEpoch = fogEpoch;
        this.subscriptions = subscriptions;
        this.codec = codec;
        this.sender = sender;
    }

    @Override
    public void accept(AggregatedValue value) {
        FogMessage.Aggregate message = FogMessage.Aggregate.of(fogId, fogEpoch, sequence.incrementAndGet(), value);
        byte[] datagram = codec.encode(message);
        List<FogSubscription> active = subscriptions.active();
        for (FogSubscription subscription : active) {
            sender.accept(datagram, subscription.callback());
        }
        LOG.debug("Sent aggregate {} to {} subscribers", message.sequence(), active.size());
    }

    public long fogEpoch() {
        return fogEpoch;
    }
}
