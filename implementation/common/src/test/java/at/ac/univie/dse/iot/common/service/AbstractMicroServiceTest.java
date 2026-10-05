package at.ac.univie.dse.iot.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ServiceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbstractMicroServiceTest {

    record TestConfig(String serviceId, int workers) implements ServiceConfig {
        @Override
        public void validate(ConfigValidator validator) {
            validator.notBlank("serviceId", serviceId).positive("workers", workers);
        }
    }

    /** Records the lifecycle calls; optionally fails in one hook. */
    static final class RecordingService extends AbstractMicroService<TestConfig> {

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final String failIn;

        RecordingService(String failIn) {
            super(TestConfig.class);
            this.failIn = failIn;
        }

        RecordingService() {
            this(null);
        }

        @Override
        protected void initialize(TestConfig config) {
            calls.add("initialize:" + config.serviceId());
            failIf("initialize");
        }

        @Override
        protected void startComponents() {
            calls.add("startComponents");
            failIf("startComponents");
        }

        @Override
        protected void stopComponents() {
            calls.add("stopComponents");
        }

        private void failIf(String hook) {
            if (hook.equals(failIn)) {
                throw new IllegalStateException("boom in " + hook);
            }
        }
    }

    private static final TestConfig CONFIG = new TestConfig("svc-1", 2);

    @Test
    void startAndStopCallHooksInOrder() {
        RecordingService service = new RecordingService();

        service.start(CONFIG);
        assertEquals(AbstractMicroService.State.RUNNING, service.state());
        assertEquals(CONFIG, service.config());

        service.stop();
        assertEquals(AbstractMicroService.State.STOPPED, service.state());
        assertEquals(List.of("initialize:svc-1", "startComponents", "stopComponents"), service.calls);
    }

    @Test
    void stopIsIdempotent() {
        RecordingService service = new RecordingService();
        service.start(CONFIG);

        service.stop();
        service.stop();

        assertEquals(1, service.calls.stream().filter("stopComponents"::equals).count());
    }

    @Test
    void serviceCannotBeStartedTwice() {
        RecordingService service = new RecordingService();
        service.start(CONFIG);

        assertThrows(IllegalStateException.class, () -> service.start(CONFIG));
        service.stop();
    }

    @Test
    void failedStartCleansUpAndTerminates() throws InterruptedException {
        RecordingService service = new RecordingService("startComponents");

        ServiceStartException e = assertThrows(ServiceStartException.class, () -> service.start(CONFIG));

        assertTrue(e.getMessage().contains("boom in startComponents"), e.getMessage());
        assertEquals(AbstractMicroService.State.FAILED, service.state());
        assertEquals(List.of("initialize:svc-1", "startComponents", "stopComponents"), service.calls);
        assertTrue(service.awaitTermination(Duration.ZERO));
    }

    @Test
    void runWithoutArgumentsReturnsUsageError() {
        assertEquals(AbstractMicroService.EXIT_USAGE, new RecordingService().run(new String[0]));
        assertEquals(AbstractMicroService.EXIT_USAGE, new RecordingService().run(new String[] {"a", "b"}));
    }

    @Test
    void runWithInvalidConfigReturnsConfigErrorWithoutStarting(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("bad.json"), "{ \"serviceId\": \"svc\", \"workers\": 0 }");
        RecordingService service = new RecordingService();

        assertEquals(AbstractMicroService.EXIT_CONFIG, service.run(new String[] {file.toString()}));
        assertTrue(service.calls.isEmpty());
        assertEquals(AbstractMicroService.State.NEW, service.state());
    }

    @Test
    void runWithMissingConfigFileReturnsConfigError(@TempDir Path dir) {
        String missing = dir.resolve("missing.json").toString();

        assertEquals(AbstractMicroService.EXIT_CONFIG, new RecordingService().run(new String[] {missing}));
    }

    @Test
    void runWithFailingStartReturnsFailure(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("ok.json"), "{ \"serviceId\": \"svc\", \"workers\": 1 }");

        assertEquals(AbstractMicroService.EXIT_FAILURE,
                new RecordingService("initialize").run(new String[] {file.toString()}));
    }

    @Test
    void runBlocksUntilStoppedAndReturnsOk(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("ok.json"), "{ \"serviceId\": \"svc\", \"workers\": 1 }");
        RecordingService service = new RecordingService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> exitCode = executor.submit(() -> service.run(new String[] {file.toString()}));

            waitForState(service, AbstractMicroService.State.RUNNING);
            assertFalse(exitCode.isDone(), "run must block while the service is running");

            service.stop();
            assertEquals(AbstractMicroService.EXIT_OK, exitCode.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void runWithLoadedConfigBlocksUntilStopped() throws Exception {
        RecordingService service = new RecordingService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> exitCode = executor.submit(() -> service.run(CONFIG));

            waitForState(service, AbstractMicroService.State.RUNNING);
            service.stop();

            assertEquals(AbstractMicroService.EXIT_OK, exitCode.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("initialize:svc-1", "startComponents", "stopComponents"), service.calls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopBeforeStartTerminatesImmediately() throws InterruptedException {
        RecordingService service = new RecordingService();

        service.stop();

        assertEquals(AbstractMicroService.State.STOPPED, service.state());
        assertTrue(service.awaitTermination(Duration.ZERO));
        assertTrue(service.calls.isEmpty());
    }

    private static void waitForState(RecordingService service, AbstractMicroService.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.state() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("service did not reach " + expected + ", state is " + service.state());
            }
            Thread.sleep(10);
        }
    }
}
