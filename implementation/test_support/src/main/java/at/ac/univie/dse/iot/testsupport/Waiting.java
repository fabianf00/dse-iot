package at.ac.univie.dse.iot.testsupport;

import java.time.Duration;
import java.util.concurrent.Callable;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;

/** Common waiting rules for asynchronous tests (Awaitility). */
public final class Waiting {

    /** Long enough for scanning, handshakes and a few aggregation windows on a busy machine. */
    public static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration HOLD_MARGIN = Duration.ofMillis(500);

    private Waiting() {
    }

    /** Waits up to {@link #TIMEOUT}, polling every 50 ms. */
    public static ConditionFactory waiting() {
        return Awaitility.await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL);
    }

    /**
     * Checks that {@code condition} stays true for {@code duration}, e.g. that nothing more
     * arrives. Fails as soon as the condition turns false (and stays false), instead of sleeping
     * first and checking once.
     */
    public static void holdsFor(Duration duration, Callable<Boolean> condition) {
        Awaitility.await()
                .during(duration)
                .atMost(duration.plus(HOLD_MARGIN))
                .pollInterval(POLL_INTERVAL)
                .until(condition);
    }

    /**
     * Pauses on purpose, e.g. to pace test data. Prefer {@link #waiting()} for everything that
     * should happen and {@link #holdsFor} for everything that should not.
     */
    public static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pausing", e);
        }
    }
}
