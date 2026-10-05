package at.ac.univie.dse.iot.testsupport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class WaitingTest {

    @Test
    void holdsForPassesWhenTheConditionStaysTrue() {
        long start = System.nanoTime();

        Waiting.holdsFor(Duration.ofMillis(200), () -> true);

        assertTrue(System.nanoTime() - start >= Duration.ofMillis(200).toNanos(), "waited the whole duration");
    }

    @Test
    void holdsForFailsWhenTheConditionTurnsFalse() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(ConditionTimeoutException.class,
                () -> Waiting.holdsFor(Duration.ofMillis(300), () -> calls.incrementAndGet() < 3));
    }
}
