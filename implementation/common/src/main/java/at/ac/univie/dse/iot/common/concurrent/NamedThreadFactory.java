package at.ac.univie.dse.iot.common.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates threads named {@code <prefix>-<n>}, so thread dumps and logs show which component a
 * thread belongs to. Uncaught exceptions are logged instead of only printed to stderr.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private static final Logger LOG = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger counter = new AtomicInteger();

    public NamedThreadFactory(String prefix, boolean daemon) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.daemon = daemon;
    }

    /** Creates a factory for non-daemon threads. */
    public NamedThreadFactory(String prefix) {
        this(prefix, false);
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler(
                (t, e) -> LOG.error("Uncaught exception in thread {}", t.getName(), e));
        return thread;
    }
}
