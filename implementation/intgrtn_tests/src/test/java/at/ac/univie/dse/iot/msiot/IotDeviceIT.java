package at.ac.univie.dse.iot.msiot;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.testsupport.CfTestPeer;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.Waiting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IT-IoT: a device publishes valid, ordered readings through the CF, two device types run from
 * the same implementation, and a device reconnects after its peer restarts.
 */
class IotDeviceIT {

    private final List<IotDeviceService> devices = new ArrayList<>();
    private final List<CfTestPeer> sinks = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        devices.forEach(IotDeviceService::stop);
        sinks.forEach(CfTestPeer::close);
    }

    @Test
    void devicePublishesThroughTheCfAndReconnectsAfterItsPeerRestarts() {
        int basePort = Ports.freeTcpRange(3);
        CfTestPeer sink = startSink(basePort);

        // 1. The device finds the sink by scanning.
        IotDeviceService temperature = startDevice("iot-test-1", "temperature", Unit.CELSIUS, 21.5, basePort + 1, basePort);
        waiting().until(() -> sink.isConnectedTo("iot-test-1"));

        // 2. Valid, ordered readings arrive.
        waiting().until(() -> sink.readingsOf("iot-test-1").size() >= 8);
        List<SensorReading> readings = sink.readingsOf("iot-test-1");
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
        waiting().until(() -> sink.readingsOf("iot-test-2").size() >= 3);
        assertTrue(sink.readingsOf("iot-test-2").stream().allMatch(r -> r.dataType().equals(DataType.of("co2"))));
        int temperatureCount = sink.readingsOf("iot-test-1").size();
        waiting().until(() -> sink.readingsOf("iot-test-1").size() > temperatureCount);

        // 4. The sink goes away and comes back on the same port; the device keeps running and reconnects.
        sink.close();
        long lastSequenceBeforeRestart = lastSequence(sink.readingsOf("iot-test-1"));
        Waiting.pause(Duration.ofMillis(500));
        assertEquals(AbstractMicroService.State.RUNNING, temperature.state());

        CfTestPeer restarted = startSink(basePort);
        waiting().until(() -> restarted.isConnectedTo("iot-test-1") || restarted.isConnectedTo("iot-test-2"));
        waiting().until(() -> !restarted.readingsOf("iot-test-1").isEmpty());
        assertTrue(restarted.readingsOf("iot-test-1").get(0).sequence() > lastSequenceBeforeRestart + 1,
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
        int port = Ports.freeTcpRange(1);
        IotDeviceService lonely = startDevice("iot-lonely", "temperature", Unit.CELSIUS, 20, port, port);

        waiting().until(() -> lonely.publishedCount() >= 3);
        assertEquals(AbstractMicroService.State.RUNNING, lonely.state());
    }

    // ---- helpers ----

    private CfTestPeer startSink(int port) {
        CfTestPeer sink = CfTestPeer.passive("sink", port);
        sinks.add(sink);
        return sink;
    }

    private IotDeviceService startDevice(String id, String dataType, Unit unit, double value, int port, int scanFrom) {
        CfConfig cf = CfConfig.builder(id, port)
                .listenHost(Ports.HOST)
                .scan(CfTestPeer.scanConfig(scanFrom, scanFrom + 2))
                .build();
        SensorConfig sensor = new SensorConfig(DataType.of(dataType), unit, 200, GeneratorConfig.constant(value), null);
        IotDeviceService service = new IotDeviceService();
        devices.add(service);
        service.start(new IotConfig(id, sensor, cf));
        return service;
    }

    private static long lastSequence(List<SensorReading> readings) {
        return readings.get(readings.size() - 1).sequence();
    }
}
