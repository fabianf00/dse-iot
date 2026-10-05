package at.ac.univie.dse.iot.msfog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import at.ac.univie.dse.iot.cf.NodeId;
import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FogConfigTest {

    private static final Path CONFIG_DIR = Path.of("..", "config");
    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void shippedConfigsAreValidAndUseDefaults() {
        FogConfig temperature = loader.load(CONFIG_DIR.resolve("fog-temp.json"), FogConfig.class);
        FogConfig co2 = loader.load(CONFIG_DIR.resolve("fog-co2.json"), FogConfig.class);

        assertEquals(DataType.of("temperature"), temperature.dataType());
        assertEquals(Unit.CELSIUS, temperature.unit());
        assertEquals(AggregationMode.AVG, temperature.aggregationMode());
        assertEquals(10_000, temperature.windowMs());
        assertEquals(50201, temperature.udp().port());
        assertEquals("0.0.0.0", temperature.udp().bindHost());
        assertEquals(120_000, temperature.udp().maxLeaseMs());
        assertEquals(32, temperature.udp().maxSubscribers());
        assertEquals(3, temperature.emptyWindowWarningAfter());
        assertEquals(NodeId.of("fog-temp"), temperature.cf().nodeId());

        assertEquals(DataType.of("co2"), co2.dataType());
        assertEquals(AggregationMode.MAX, co2.aggregationMode());
        assertEquals(50202, co2.udp().port());
    }

    @Test
    void invalidConfigListsEveryProblem() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse("""
                { "serviceId": "fog-x", "dataType": "temperature", "aggregationMode": "AVG",
                  "windowMs": 0, "udp": { "port": 0 }, "cf": { "listenPort": 50121 } }
                """, FogConfig.class, "fog-x.json"));

        assertEquals(List.of("unit must be set", "windowMs must be > 0", "udp.port must be between 1 and 65535"),
                e.problems());
    }

    @Test
    void unknownAggregationModeIsReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.parse("""
                { "serviceId": "fog-x", "dataType": "temperature", "unit": "CELSIUS", "aggregationMode": "MEDIAN",
                  "windowMs": 1000, "udp": { "port": 1 }, "cf": { "listenPort": 2 } }
                """, FogConfig.class, "fog-x.json"));

        assertEquals(true, e.getMessage().contains("aggregationMode"), e.getMessage());
    }
}
