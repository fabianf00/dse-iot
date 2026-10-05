package at.ac.univie.dse.iot.common.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConcurrencyHelpersTest {

    @Test
    void threadsAreNamedWithPrefixAndCounter() {
        NamedThreadFactory factory = new NamedThreadFactory("cf-reader", true);

        Thread first = factory.newThread(() -> { });
        Thread second = factory.newThread(() -> { });

        assertEquals("cf-reader-1", first.getName());
        assertEquals("cf-reader-2", second.getName());
        assertTrue(first.isDaemon());
        assertFalse(new NamedThreadFactory("worker").newThread(() -> { }).isDaemon());
    }

    @Test
    void shutdownGracefullyLetsRunningTasksFinish() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            sleep(100);
            finished.set(true);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(ExecutorUtils.shutdownGracefully(executor, Duration.ofSeconds(5)));
        assertTrue(finished.get());
    }

    @Test
    void shutdownGracefullyInterruptsTasksThatDoNotFinishInTime() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(ExecutorUtils.shutdownGracefully(executor, Duration.ofMillis(100)));
        assertTrue(interrupted.get());
    }

    @Test
    void shutdownGracefullyAcceptsNull() {
        assertTrue(ExecutorUtils.shutdownGracefully(null, Duration.ofMillis(1)));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
