package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands DATA messages to the local listeners on a dedicated thread, so network threads never run
 * MS code and MS code never blocks the network. Listeners are called one message at a time, in
 * arrival order. If the listeners fall behind by more than {@code queueCapacity} messages, new
 * messages are dropped.
 */
final class MessageDispatcher implements MessageRouter.LocalDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(MessageDispatcher.class);

    private final SubscriptionRegistry subscriptions;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    MessageDispatcher(SubscriptionRegistry subscriptions, ThreadFactory threadFactory, int queueCapacity) {
        this.subscriptions = subscriptions;
        // Threads are created lazily on the first message.
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory);
    }

    @Override
    public void deliver(CfMessage message) {
        List<MessageListener> listeners = subscriptions.matching(message.topic());
        if (listeners.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> notifyListeners(listeners, message));
        } catch (RejectedExecutionException e) {
            if (!executor.isShutdown()) {
                long count = dropped.incrementAndGet();
                LOG.warn("Listeners are too slow, dropped message {} on {} ({} dropped so far)",
                        message.id(), message.topic(), count);
            }
        }
    }

    void close() {
        ExecutorUtils.shutdownGracefully(executor, Duration.ofSeconds(1));
    }

    private static void notifyListeners(List<MessageListener> listeners, CfMessage message) {
        for (MessageListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (RuntimeException e) {
                LOG.error("Listener failed on message {} (topic {})", message.id(), message.topic(), e);
            }
        }
    }
}
