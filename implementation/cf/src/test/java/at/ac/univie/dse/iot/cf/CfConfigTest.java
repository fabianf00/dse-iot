package at.ac.univie.dse.iot.cf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class CfConfigTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void minimalJsonConfigUsesDefaults() {
        CfConfig config = loader.parse("""
                {
                  "nodeId": "iot-temp-1",
                  "listenPort": 50101,
                  "scan": { "hosts": ["127.0.0.1"], "portRange": { "from": 50100, "to": 50129 } }
                }
                """, CfConfig.class, "cf.json");

        assertEquals(NodeId.of("iot-temp-1"), config.nodeId());
        assertEquals("0.0.0.0", config.listenHost());
        assertEquals("127.0.0.1", config.advertisedHost());
        assertEquals(3, config.maxOutgoingPeers());
        assertEquals(6, config.defaultTtl());
        assertEquals(60_000, config.peerRefreshIntervalMs());
        assertEquals(5_000, config.heartbeatIntervalMs());
        assertEquals(15_000, config.heartbeatTimeoutMs());
        assertEquals(60_000, config.seenCache().expiryMs());
        assertEquals(64 * 1024, config.maxFrameBytes());
        assertEquals(List.of("127.0.0.1"), config.scan().hosts());
        assertEquals(500, config.scan().intervalMs());
        assertEquals(10_000, config.scan().failedCandidateBackoffMs());
    }

    @Test
    void configWithoutScanIsPassive() {
        CfConfig config = loader.parse("{ \"nodeId\": \"n\", \"listenPort\": 50101 }", CfConfig.class, "cf.json");

        assertNull(config.scan());
    }

    @Test
    void invalidValuesAreReportedWithPaths() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse("""
                {
                  "nodeId": "n",
                  "listenPort": 70000,
                  "advertisedHost": "0.0.0.0",
                  "heartbeatIntervalMs": 5000,
                  "heartbeatTimeoutMs": 4000,
                  "scan": { "hosts": [], "portRange": { "from": 50110, "to": 50100 } }
                }
                """, CfConfig.class, "cf.json"));

        assertEquals(List.of(
                "listenPort must be between 1 and 65535",
                "advertisedHost must be an address peers can connect to",
                "heartbeatTimeoutMs must be > heartbeatIntervalMs",
                "scan.hosts must not be empty",
                "scan.portRange.to must be >= from"), e.problems());
    }

    @Test
    void invalidNodeIdIsReported() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> loader.parse("{ \"nodeId\": \"bad id\", \"listenPort\": 1 }", CfConfig.class, "cf.json"));

        assertTrue(e.getMessage().contains("nodeId"), e.getMessage());
    }

    @Test
    void missingNodeIdDefaultsToTheServiceId() {
        // Built without validation: on its own, a config without nodeId is invalid.
        CfConfig config = new CfConfig(null, null, 50101, null, null, 0, 0, 0, 0, 0, null, 0, 0);

        assertEquals(NodeId.of("iot-temp-1"), config.withDefaultNodeId("iot-temp-1").nodeId());
        assertEquals(NodeId.of("explicit"),
                CfConfig.builder("explicit", 1).build().withDefaultNodeId("iot-temp-1").nodeId());
        assertNull(config.withDefaultNodeId("not a node id").nodeId());
    }

    @Test
    void factoryRejectsInvalidConfig() {
        CfConfig invalid = CfConfig.builder("n", 0).build();

        assertThrows(ConfigException.class, () -> CommunicationFrameworkFactory.create(invalid));
    }
}
