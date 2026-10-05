package at.ac.univie.dse.iot.clntml;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IT-CLNT (client side): the real client and real RMI against an exported fake ML service.
 * The full pipeline with real services is the end-to-end scenario of the deployment step.
 */
class MlClientIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path dir;

    private int registryPort;
    private Registry registry;
    private FakeMlService exported;
    private Path config;

    @BeforeEach
    void setUp() throws IOException {
        registryPort = freeTcpPort();
        config = Files.writeString(dir.resolve("clnt.json"), """
                { "pollIntervalMs": 200, "endpoints": [ { "name": "ml-1", "host": "127.0.0.1", "registryPort": %d } ] }
                """.formatted(registryPort));
    }

    @AfterEach
    void tearDown() {
        stopServer();
    }

    @Test
    void onceModePrintsOnlyWhatIsNewSinceTheLastRun() throws Exception {
        FakeMlService service = new FakeMlService(7)
                .withPrediction("co2", 680, 720, "PPM")
                .withPrediction("temperature", 21, 22, "CELSIUS");
        startServer(service);
        Path cursors = dir.resolve("cursors.json");

        Output first = runClient(config.toString(), "--once", "--cursor-file", cursors.toString());
        assertEquals(AbstractMicroService.EXIT_OK, first.exitCode());
        assertTrue(first.contains("PREDICTION  co2"), first.text());
        assertTrue(first.contains("PREDICTION  temperature"), first.text());

        service.withAnomaly("temp-sudden-drop", 14.2);
        Output second = runClient(config.toString(), "--once", "--cursor-file", cursors.toString());
        assertEquals(AbstractMicroService.EXIT_OK, second.exitCode());
        assertTrue(second.contains("ANOMALY     temp-sudden-drop   expected temperature > 20.0 °C, observed 14.2 °C"),
                second.text());
        assertTrue(!second.contains("PREDICTION"), "events of the first run are not printed again: " + second.text());
    }

    @Test
    void statusModePrintsTheServiceStatus() throws Exception {
        startServer(new FakeMlService(7).withPrediction("co2", 680, 720, "PPM"));

        Output output = runClient(config.toString(), "--status");

        assertEquals(AbstractMicroService.EXIT_OK, output.exitCode());
        assertTrue(output.contains("STATUS      ml-fake at rmi://127.0.0.1:" + registryPort + "/MlService"), output.text());
        assertTrue(output.contains("    data types:  co2, temperature"), output.text());
        assertTrue(output.contains("    fog-co2: PENDING (no aggregates yet)"), output.text());
        assertTrue(output.contains("    fog-temp: ACTIVE (3 aggregates, last 1 s ago)"), output.text());
        assertTrue(output.contains("    events:      1"), output.text());
    }

    @Test
    void statusModeFailsWhenTheServiceIsUnreachable() {
        Output output = runClient(config.toString(), "--status");

        assertEquals(AbstractMicroService.EXIT_FAILURE, output.exitCode());
        assertTrue(output.contains("WARN        unreachable"), output.text());
    }

    @Test
    void onceModeFailsWhenTheServiceIsUnreachable() {
        Output output = runClient(config.toString(), "--once");

        assertEquals(AbstractMicroService.EXIT_FAILURE, output.exitCode());
        assertTrue(output.contains("WARN        unreachable"), output.text());
    }

    @Test
    void pollingSurvivesAServerRestart() throws Exception {
        startServer(new FakeMlService(7).withPrediction("co2", 680, 720, "PPM"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MlClient client = new MlClient(new PrintStream(bytes, true, StandardCharsets.UTF_8), null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ClientConfig loaded = new ConfigLoader().load(config, ClientConfig.class);
            Future<Integer> exitCode = executor.submit(() -> client.run(loaded));
            await().atMost(TIMEOUT).until(() -> text(bytes).contains("PREDICTION  co2"));
            await().atMost(TIMEOUT).until(() -> text(bytes).contains("-- no new anomalies or prediction changes --"));

            stopServer();
            await().atMost(TIMEOUT).until(() -> text(bytes).contains("WARN        unreachable"));
            assertEquals(AbstractMicroService.State.RUNNING, client.state(), "the client keeps running");

            startServer(new FakeMlService(8).withAnomaly("temp-sudden-drop", 14.2));
            await().atMost(TIMEOUT).until(() -> text(bytes).contains("ANOMALY     temp-sudden-drop"));
            String output = text(bytes);
            assertTrue(output.contains("INFO        server restarted (new epoch), cursor reset"), output);
            assertTrue(output.contains("INFO        reachable again"), output);

            client.stop();
            assertEquals(AbstractMicroService.EXIT_OK, exitCode.get(10, TimeUnit.SECONDS));
        } finally {
            client.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void invalidCommandLineAndConfigGiveDistinctExitCodes() throws IOException {
        Output usage = runClient("--once");
        assertEquals(AbstractMicroService.EXIT_USAGE, usage.exitCode());
        assertTrue(usage.errors().contains("Usage: java -jar clnt_ml.jar"), usage.errors());

        Path broken = Files.writeString(dir.resolve("broken.json"), "{ \"endpoints\": [] }");
        Output invalid = runClient(broken.toString());
        assertEquals(AbstractMicroService.EXIT_CONFIG, invalid.exitCode());
        assertTrue(invalid.errors().contains("endpoints must not be empty"), invalid.errors());
    }

    // ---- helpers ----

    private record Output(int exitCode, String text, String errors) {
        boolean contains(String fragment) {
            return text.contains(fragment);
        }
    }

    private static Output runClient(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = MlClientApplication.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Output(exitCode, text(out), text(err));
    }

    private static String text(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void startServer(FakeMlService service) throws RemoteException {
        registry = LocateRegistry.createRegistry(registryPort);
        MlRemoteService stub = (MlRemoteService) UnicastRemoteObject.exportObject(service, 0);
        registry.rebind(MlRemoteService.DEFAULT_BIND_NAME, stub);
        exported = service;
    }

    private void stopServer() {
        try {
            if (exported != null) {
                UnicastRemoteObject.unexportObject(exported, true);
            }
            if (registry != null) {
                UnicastRemoteObject.unexportObject(registry, true);
            }
        } catch (NoSuchObjectException e) {
            // Already stopped.
        }
        exported = null;
        registry = null;
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
