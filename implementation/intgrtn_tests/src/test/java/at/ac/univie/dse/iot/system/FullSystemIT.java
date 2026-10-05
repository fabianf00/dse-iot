package at.ac.univie.dse.iot.system;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.clntml.MlClient;
import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import at.ac.univie.dse.iot.system.TestConfigs.Layout;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.RestTestClient;
import at.ac.univie.dse.iot.testsupport.RestTestClient.Response;
import at.ac.univie.dse.iot.testsupport.UdpFogTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IT-E2E: the full pipeline in one JVM. IoT devices → CF → fogs → UDP → ML → RMI (CLNT ML) and
 * REST (stored results, comments) → WEB ML. One device injects a temperature drop about 19 s
 * after start (see {@link TestConfigs}); the whole test takes about 20–30 s.
 */
class FullSystemIT {

    private static final Duration SYSTEM_TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    Path dataDir;

    private RunningSystem system;
    private RestTestClient rest;

    @BeforeEach
    void start() {
        system = RunningSystem.start(dataDir);
        rest = new RestTestClient(system.layout().restUrl());
    }

    @AfterEach
    void stop() {
        system.close();
    }

    @Test
    void anomalyTravelsFromTheDeviceToRmiRestAndTheWebUi() throws Exception {
        Layout layout = system.layout();

        // 1. Each fog aggregates the readings of its 3 devices.
        assertFogSeesDevices(layout.fogTempUdp(), 3);
        assertFogSeesDevices(layout.fogCo2Udp(), 3);

        // 2. ML is ACTIVE at both fogs; RMI and REST report the same.
        MlRemoteService rmi = (MlRemoteService) LocateRegistry.getRegistry(Ports.HOST, layout.mlRegistry())
                .lookup("MlService");
        waiting().atMost(SYSTEM_TIMEOUT).until(() -> rmi.getStatus().fogStatus().values().stream()
                .allMatch(status -> status.startsWith("ACTIVE")));
        ServiceStatusDto rmiStatus = rmi.getStatus();
        JsonNode restStatus = rest.get("/status").json();
        assertEquals(rmiStatus.serviceId(), restStatus.get("serviceId").asText());
        assertEquals(rmiStatus.serverEpoch(), restStatus.get("serverEpoch").asLong());
        assertEquals(Set.of("fog-temp", "fog-co2"), rmiStatus.fogStatus().keySet());
        restStatus.get("fogStatus").fields().forEachRemaining(fog ->
                assertTrue(fog.getValue().asText().startsWith("ACTIVE"), fog.toString()));

        // 3. CLNT ML --once prints the injected drop.
        Path cursors = dataDir.resolve("cursors.json");
        StringBuilder printed = new StringBuilder();
        waiting().atMost(SYSTEM_TIMEOUT).pollInterval(Duration.ofSeconds(1)).until(() -> {
            printed.append(runClientOnce(cursors));
            return printed.toString().contains("ANOMALY     " + TestConfigs.CHAIN);
        });
        assertTrue(printed.toString().contains("PREDICTION  temperature"), printed.toString());
        assertTrue(printed.toString().contains("PREDICTION  co2"), printed.toString());

        // 4. The anomaly is stored and can be commented over REST.
        waiting().until(() -> rest.get("/anomalies?chainId=" + TestConfigs.CHAIN).json().get("total").asInt() >= 1);
        JsonNode anomaly = rest.get("/anomalies?chainId=" + TestConfigs.CHAIN).json().get("items").get(0);
        assertEquals("temperature", anomaly.get("dataType").asText());
        assertTrue(anomaly.get("observedValue").asDouble() < 20, anomaly.toString());
        long id = anomaly.get("id").asLong();
        Response comment = rest.post("/anomalies/" + id + "/comments", Map.of("text", "found by FullSystemIT"));
        assertEquals(201, comment.status(), comment.body());
        assertEquals("found by FullSystemIT",
                rest.get("/anomalies/" + id).json().get("comments").get(0).get("text").asText());

        // 5. WEB ML serves the UI and points it to this ML.
        RestTestClient web = new RestTestClient("http://" + Ports.HOST + ":" + layout.webPort());
        assertEquals(200, web.get("/").status());
        assertEquals(layout.restUrl(), web.get("/config.json").json().get("endpoints").get(0).get("restUrl").asText());

        assertEquals(List.of(), system.notRunning());
    }

    /** Subscribes at the fog and waits for an aggregate of {@code devices} devices. */
    private static void assertFogSeesDevices(int fogUdpPort, int devices) {
        try (UdpFogTestClient client = new UdpFogTestClient(new InetSocketAddress(Ports.HOST, fogUdpPort))) {
            client.request(FogMessage.Subscribe.of("system-test", null, client.port(), 30_000),
                    FogMessage.SubscribeAck.class);
            waiting().atMost(SYSTEM_TIMEOUT).until(() -> {
                FogMessage.Aggregate aggregate = client.pollAggregate(Duration.ofSeconds(2));
                return aggregate != null && aggregate.deviceCount() == devices;
            });
        }
    }

    private String runClientOnce(Path cursors) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = new MlClient(new PrintStream(out, true, StandardCharsets.UTF_8), cursors)
                .runOnce(TestConfigs.client(system.layout()));
        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }
}
