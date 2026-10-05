package at.ac.univie.dse.iot.msiot;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.cf.CommunicationFramework;
import at.ac.univie.dse.iot.cf.CommunicationFrameworkFactory;
import at.ac.univie.dse.iot.cf.NodeId;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Topics;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IT-IoT: a device publishes valid, ordered readings through the CF, two device types run from
 * the same implementation, and a device reconnects after its peer restarts.
 */
class IotDeviceIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final List<IotDeviceService> devices = new ArrayList<>();
    private final List<CommunicationFramework> sinks = new ArrayList<>();
    private final List<SensorReading> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEverything() {
        devices.forEach(IotDeviceService::stop);
        sinks.forEach(CommunicationFramework::close);
    }

    @Test
    void devicePublishesThroughTheCfAndReconnectsAfterItsPeerRestarts() {
        int basePort = freePorts(3);
        int sinkPort = basePort;
        CommunicationFramework sink = startSink(sinkPort);

        // 1. The device finds the sink by scanning.
        IotDeviceService temperature = startDevice("iot-test-1", "temperature", Unit.CELSIUS, 21.5, basePort + 1, basePort);
        await().atMost(TIMEOUT).until(() -> isConnected(sink, "iot-test-1"));

        // 2. Valid, ordered readings arrive.
        await().atMost(TIMEOUT).until(() -> readingsOf("iot-test-1").size() >= 8);
        List<SensorReading> readings = readingsOf("iot-test-1");
        for (SensorReading reading : readings) {
            assertEquals(DataType.of("temperature"), reading.dataType());
            assertEquals(Unit.CELSIUS, reading.unit());
            assertEquals(21.5, reading.value());
            assertEquals(readings.get(0).bootId(), reading.bootId());
        }
        for (int i = 1; i < readings.size(); i++) {
            assertTrue(readings.get(i).sequence() > readings.get(i - 1).sequence(), "strictly increasing sequence");
        }

        // 3. A second device type runs from the same implementation (SIMI); temperature keeps flowing.
        startDevice("iot-test-2", "co2", Unit.PPM, 650, basePort + 2, basePort);
        await().atMost(TIMEOUT).until(() -> readingsOf("iot-test-2").size() >= 3);
        assertTrue(readingsOf("iot-test-2").stream().allMatch(r -> r.dataType().equals(DataType.of("co2"))));
        int temperatureCount = readingsOf("iot-test-1").size();
        await().atMost(TIMEOUT).until(() -> readingsOf("iot-test-1").size() > temperatureCount);

        // 4. The sink goes away and comes back on the same port; the device keeps running and reconnects.
        sink.close();
        long lastSequenceBeforeRestart = lastSequence("iot-test-1");
        received.clear();
        sleep(500);
        assertEquals(AbstractMicroService.State.RUNNING, temperature.state());

        CommunicationFramework restarted = startSink(sinkPort);
        await().atMost(TIMEOUT).until(() -> isConnected(restarted, "iot-test-1") || isConnected(restarted, "iot-test-2"));
        await().atMost(TIMEOUT).until(() -> !readingsOf("iot-test-1").isEmpty());
        assertTrue(readingsOf("iot-test-1").get(0).sequence() > lastSequenceBeforeRestart + 1,
                "sequence continues; readings published while the sink was down are missing");
    }

    @Test
    void invalidConfigFileFailsFastWithNonZeroExitCode(@TempDir Path dir) throws IOException {
        Path config = Files.writeString(dir.resolve("iot-test-3.json"), """
                {
                  "serviceId": "iot-test-3",
                  "sensor": { "dataType": "temperature", "unit": "CELSIUS", "publishIntervalMs": 0,
                              "generator": { "kind": "CONSTANT", "base": 20 } },
                  "cf": { "listenPort": 50101 }
                }
                """);
        IotDeviceService service = new IotDeviceService();

        int exitCode = service.run(new String[] {config.toString()});

        assertEquals(AbstractMicroService.EXIT_CONFIG, exitCode);
        assertEquals(AbstractMicroService.State.NEW, service.state());
    }

    @Test
    void deviceWithoutPeersKeepsRunning() {
        int port = freePorts(1);
        IotDeviceService lonely = startDevice("iot-lonely", "temperature", Unit.CELSIUS, 20, port, port);

        await().atMost(TIMEOUT).until(() -> lonely.publishedCount() >= 3);
        assertEquals(AbstractMicroService.State.RUNNING, lonely.state());
    }

    // ---- helpers ----

    private CommunicationFramework startSink(int port) {
        CommunicationFramework sink = CommunicationFrameworkFactory.create(
                CfConfig.builder("sink", port).listenHost(HOST).build());
        sink.subscribe(Topics.ALL_SENSORS, message -> received.add(message.payloadAs(SensorReading.class)));
        sink.start();
        sinks.add(sink);
        return sink;
    }

    private IotDeviceService startDevice(String id, String dataType, Unit unit, double value, int port, int scanFrom) {
        CfConfig cf = CfConfig.builder(id, port)
                .listenHost(HOST)
                .scan(new CfConfig.ScanConfig(List.of(HOST), new CfConfig.PortRange(scanFrom, scanFrom + 2),
                        50, 200, 500, 200, 3))
                .build();
        SensorConfig sensor = new SensorConfig(DataType.of(dataType), unit, 200, GeneratorConfig.constant(value), null);
        IotDeviceService service = new IotDeviceService();
        devices.add(service);
        service.start(new IotConfig(id, sensor, cf));
        return service;
    }

    private static boolean isConnected(CommunicationFramework node, String peer) {
        return node.connectedPeers().stream().anyMatch(p -> p.nodeId().equals(NodeId.of(peer)));
    }

    private List<SensorReading> readingsOf(String deviceId) {
        return received.stream().filter(r -> r.deviceId().equals(deviceId)).toList();
    }

    private long lastSequence(String deviceId) {
        List<SensorReading> readings = readingsOf(deviceId);
        return readings.get(readings.size() - 1).sequence();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePorts(int count) {
        for (int attempt = 0; attempt < 50; attempt++) {
            int base = ThreadLocalRandom.current().nextInt(40_000, 60_000);
            if (allFree(base, count)) {
                return base;
            }
        }
        throw new IllegalStateException("no free port range found");
    }

    private static boolean allFree(int base, int count) {
        for (int port = base; port < base + count; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(HOST, port));
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}
