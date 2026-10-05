package at.ac.univie.dse.iot.testsupport;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.cf.CommunicationFramework;
import at.ac.univie.dse.iot.cf.CommunicationFrameworkFactory;
import at.ac.univie.dse.iot.cf.NodeId;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Topics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A CF node for tests: records every sensor reading it receives and can publish readings, so it
 * can stand in for IoT devices (publisher) or for a fog node (sink).
 */
public final class CfTestPeer implements AutoCloseable {

    private final CommunicationFramework cf;
    private final List<SensorReading> readings = new CopyOnWriteArrayList<>();

    private CfTestPeer(CfConfig config) {
        this.cf = CommunicationFrameworkFactory.create(config);
        cf.subscribe(Topics.ALL_SENSORS, message -> readings.add(message.payloadAs(SensorReading.class)));
        cf.start();
    }

    /** A started peer that accepts links but does not scan for peers itself. */
    public static CfTestPeer passive(String nodeId, int port) {
        return new CfTestPeer(CfConfig.builder(nodeId, port).listenHost(Ports.HOST).build());
    }

    /** A started peer that scans {@code scanFrom..scanTo} on the loopback interface (fast test timings). */
    public static CfTestPeer scanning(String nodeId, int port, int scanFrom, int scanTo) {
        return new CfTestPeer(CfConfig.builder(nodeId, port)
                .listenHost(Ports.HOST)
                .scan(scanConfig(scanFrom, scanTo))
                .build());
    }

    /** Scan settings with short intervals and backoff for tests. */
    public static CfConfig.ScanConfig scanConfig(int scanFrom, int scanTo) {
        return new CfConfig.ScanConfig(List.of(Ports.HOST), new CfConfig.PortRange(scanFrom, scanTo),
                50, 200, 500, 200, 3);
    }

    /** Publishes a reading on its data type's topic. */
    public void publish(SensorReading reading) {
        cf.publish(Topics.sensor(reading.dataType()), reading);
    }

    /** Publishes any payload on any topic, e.g. a reading on the wrong topic. */
    public void publish(String topic, Object payload) {
        cf.publish(topic, payload);
    }

    /** All readings received so far, in arrival order. */
    public List<SensorReading> readings() {
        return List.copyOf(readings);
    }

    public List<SensorReading> readingsOf(String deviceId) {
        return readings.stream().filter(r -> r.deviceId().equals(deviceId)).toList();
    }

    public void clearReadings() {
        readings.clear();
    }

    public boolean isConnectedTo(String nodeId) {
        return cf.connectedPeers().stream().anyMatch(peer -> peer.nodeId().equals(NodeId.of(nodeId)));
    }

    @Override
    public void close() {
        cf.close();
    }
}
