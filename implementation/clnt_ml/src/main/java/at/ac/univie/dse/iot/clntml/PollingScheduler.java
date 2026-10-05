package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls every endpoint in its own task with a fixed delay, so a slow or unreachable endpoint
 * does not hold up the others.
 */
public final class PollingScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PollingScheduler.class);

    private final List<EndpointPoller> pollers;
    private ScheduledExecutorService executor;

    public PollingScheduler(List<EndpointPoller> pollers) {
        this.pollers = List.copyOf(pollers);
    }

    public synchronized void start(Duration interval, ThreadFactory threadFactory) {
        if (executor != null) {
            throw new IllegalStateException("polling already started");
        }
        executor = Executors.newScheduledThreadPool(pollers.size(), threadFactory);
        for (EndpointPoller poller : pollers) {
            executor.scheduleWithFixedDelay(() -> guardedPoll(poller), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Polls every endpoint once, one after the other; returns {@code true} if all answered. */
    public boolean pollAllOnce() {
        boolean allAnswered = true;
        for (EndpointPoller poller : pollers) {
            allAnswered &= poller.pollOnce();
        }
        return allAnswered;
    }

    public synchronized void stop() {
        ExecutorUtils.shutdownGracefully(executor, Duration.ofSeconds(5));
    }

    public List<EndpointPoller> pollers() {
        return pollers;
    }

    private static void guardedPoll(EndpointPoller poller) {
        try {
            poller.pollOnce();
        } catch (RuntimeException e) {
            LOG.error("Polling {} failed", poller.name(), e);
        }
    }
}
