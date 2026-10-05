package at.ac.univie.dse.iot.cf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flooding with TTL and duplicate detection.
 *
 * <p>A received DATA message is dropped if its id was seen before. Otherwise it is delivered to
 * the local listeners and, if its TTL allows another hop, forwarded with TTL − 1 to every
 * connected peer except the one it came from.
 */
final class MessageRouter {

    private static final Logger LOG = LoggerFactory.getLogger(MessageRouter.class);

    /** Sends a message on every link except the one to {@code except} ({@code null} = all links). */
    @FunctionalInterface
    interface Broadcaster {
        void broadcast(CfMessage message, NodeId except);
    }

    /** Hands a message to the local subscribers. */
    @FunctionalInterface
    interface LocalDelivery {
        void deliver(CfMessage message);
    }

    private final NodeId self;
    private final int defaultTtl;
    private final SeenMessageCache seenMessages;
    private final Broadcaster broadcaster;
    private final LocalDelivery localDelivery;
    private final LongSupplier clock;
    private final AtomicLong duplicatesDropped = new AtomicLong();

    MessageRouter(NodeId self, int defaultTtl, SeenMessageCache seenMessages, Broadcaster broadcaster,
            LocalDelivery localDelivery, LongSupplier clock) {
        this.self = self;
        this.defaultTtl = defaultTtl;
        this.seenMessages = seenMessages;
        this.broadcaster = broadcaster;
        this.localDelivery = localDelivery;
        this.clock = clock;
    }

    /** Creates a DATA message originating here and sends it to all peers. */
    CfMessage publish(String topic, JsonNode payload) {
        CfMessage message = CfMessage.data(self, topic, payload, defaultTtl, clock.getAsLong());
        seenMessages.markIfNew(message.id());
        broadcaster.broadcast(message, null);
        return message;
    }

    /** Handles a DATA message received from the direct peer {@code from}. */
    void onData(CfMessage message, NodeId from) {
        if (message.type() != MessageType.DATA) {
            return;
        }
        if (message.origin().equals(self)) {
            // Our own message came back after its id expired from the cache.
            return;
        }
        if (!seenMessages.markIfNew(message.id())) {
            duplicatesDropped.incrementAndGet();
            LOG.trace("Dropped duplicate {} from {}", message.id(), from);
            return;
        }
        localDelivery.deliver(message);
        if (message.ttl() > 1) {
            broadcaster.broadcast(message.forwardedBy(self), from);
        }
    }

    long duplicatesDropped() {
        return duplicatesDropped.get();
    }
}
