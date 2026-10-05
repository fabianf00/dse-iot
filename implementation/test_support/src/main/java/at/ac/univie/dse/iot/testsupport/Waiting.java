package at.ac.univie.dse.iot.testsupport;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;

/** Common waiting rules for asynchronous tests (Awaitility). */
public final class Waiting {

    /** Long enough for scanning, handshakes and a few aggregation windows on a busy machine. */
    public static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private Waiting() {
    }

    /** Waits up to {@link #TIMEOUT}, polling every 50 ms. */
    public static ConditionFactory waiting() {
        return Awaitility.await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL);
    }

    /**
     * Pauses on purpose, e.g. to prove that something does <em>not</em> happen within a time.
     * Prefer {@link #waiting()} for everything that should happen.
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
