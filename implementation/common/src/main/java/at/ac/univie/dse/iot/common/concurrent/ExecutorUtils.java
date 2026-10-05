package at.ac.univie.dse.iot.common.concurrent;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers for executor lifecycles. */
public final class ExecutorUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorUtils.class);

    private ExecutorUtils() {
    }

    /**
     * Stops accepting tasks and waits up to {@code timeout} for running tasks to finish. Tasks
     * still running afterwards are interrupted, and the method waits up to {@code timeout} once
     * more.
     *
     * @return {@code true} if the executor terminated
     */
    public static boolean shutdownGracefully(ExecutorService executor, Duration timeout) {
        if (executor == null) {
            return true;
        }
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            executor.shutdownNow();
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            LOG.warn("Executor did not terminate within {} after being interrupted", timeout);
            return false;
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }
}
