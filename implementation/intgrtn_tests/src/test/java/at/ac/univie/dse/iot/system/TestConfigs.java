package at.ac.univie.dse.iot.system;

import at.ac.univie.dse.iot.cf.CfConfig;
import at.ac.univie.dse.iot.clntml.ClientConfig;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msfog.FogConfig;
import at.ac.univie.dse.iot.msiot.AnomalyInjectionConfig;
import at.ac.univie.dse.iot.msiot.GeneratorConfig;
import at.ac.univie.dse.iot.msiot.IotConfig;
import at.ac.univie.dse.iot.msiot.SensorConfig;
import at.ac.univie.dse.iot.msml.MlConfig;
import at.ac.univie.dse.iot.testsupport.CfTestPeer;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.webml.WebMlConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Configs of the in-process system: the minimum layout with short intervals and windows, all on
 * free ports of 127.0.0.1.
 *
 * <p>The values are constant, so the only anomaly is the injected one. Device {@code iot-temp-1}
 * injects -12 °C for 10 s at its 76th reading (seed {@value #INJECTION_SEED}, probability
 * {@value #INJECTION_PROBABILITY}), about 19 s after start. The fog then averages
 * (23 + 23 + 11) / 3 = 19 °C, which breaks the chain {@value #CHAIN} (temperature &gt; 20).
 */
final class TestConfigs {

    static final String CHAIN = "temp-sudden-drop";
    static final DataType TEMPERATURE = DataType.of("temperature");
    static final DataType CO2 = DataType.of("co2");
    static final List<String> TEMPERATURE_DEVICES = List.of("iot-temp-1", "iot-temp-2", "iot-temp-3");
    static final List<String> CO2_DEVICES = List.of("iot-co2-1", "iot-co2-2", "iot-co2-3");
    static final long INJECTION_SEED = 273;
    static final double INJECTION_PROBABILITY = 0.02;

    private static final long PUBLISH_INTERVAL_MS = 250;
    private static final long WINDOW_MS = 1_000;
    private static final int CF_NODES = 8;

    /** Ports of one system; picked once, so ML can find the fogs and the client ML. */
    record Layout(int cfFrom, int fogTempUdp, int fogCo2Udp, int mlCallbackUdp, int mlRegistry, int mlRest,
            int webPort) {

        static Layout pick() {
            return new Layout(Ports.freeTcpRange(CF_NODES), Ports.freeUdpPort(), Ports.freeUdpPort(),
                    Ports.freeUdpPort(), Ports.freeTcpPort(), Ports.freeTcpPort(), Ports.freeTcpPort());
        }

        int cfTo() {
            return cfFrom + CF_NODES - 1;
        }

        String restUrl() {
            return "http://" + Ports.HOST + ":" + mlRest + "/api/v1";
        }
    }

    private TestConfigs() {
    }

    /** CF ports: the fogs first, then the devices. */
    static IotConfig device(Layout layout, String id, int index) {
        boolean temperature = id.startsWith("iot-temp");
        GeneratorConfig generator = new GeneratorConfig(GeneratorConfig.Kind.CONSTANT, temperature ? 23.0 : 600.0,
                0, 0, 0, 0, 0, 0, INJECTION_SEED, null, null);
        AnomalyInjectionConfig injection = id.equals("iot-temp-1")
                ? new AnomalyInjectionConfig(true, INJECTION_PROBABILITY, -12.0, 10)
                : null;
        SensorConfig sensor = new SensorConfig(temperature ? TEMPERATURE : CO2, temperature ? Unit.CELSIUS : Unit.PPM,
                PUBLISH_INTERVAL_MS, generator, injection);
        return new IotConfig(id, sensor, cf(layout, id, layout.cfFrom() + 2 + index));
    }

    static FogConfig fog(Layout layout, DataType dataType) {
        boolean temperature = dataType.equals(TEMPERATURE);
        String id = temperature ? "fog-temp" : "fog-co2";
        int udp = temperature ? layout.fogTempUdp() : layout.fogCo2Udp();
        return new FogConfig(id, dataType, temperature ? Unit.CELSIUS : Unit.PPM, AggregationMode.AVG, WINDOW_MS, 0,
                new FogConfig.UdpConfig(Ports.HOST, udp, 0, 0), cf(layout, id, layout.cfFrom() + (temperature ? 0 : 1)));
    }

    static MlConfig ml(Layout layout, Path dataDir) {
        return new ConfigLoader().parse("""
                {
                  "serviceId": "ml-1",
                  "fogNodes": [
                    { "id": "fog-temp", "host": "%1$s", "port": %2$d },
                    { "id": "fog-co2", "host": "%1$s", "port": %3$d }
                  ],
                  "udp": { "bindHost": "%1$s", "callbackPort": %4$d, "leaseMs": 5000, "subscribeRetryMaxMs": 1000 },
                  "workers": { "threads": 2, "queueCapacity": 100 },
                  "timeSeriesRetentionSeconds": 60,
                  "anomaly": { "minSamplesPerPhase": 2, "chains": [ {
                    "id": "%5$s", "description": "a warm room stays warm",
                    "past":    { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 4, "toSec": 2 },
                    "current": { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 2, "toSec": 0 },
                    "future":  { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "withinSec": 3 }
                  } ] },
                  "prediction": { "histograms": [
                    { "dataType": "temperature", "binWidth": 0.5, "maxSamples": 30 },
                    { "dataType": "co2", "binWidth": 25, "maxSamples": 30 }
                  ] },
                  "rmi": { "hostname": "%1$s", "registryPort": %6$d, "bindName": "MlService" },
                  "storage": { "jdbcUrl": "jdbc:h2:file:%7$s" },
                  "rest": { "host": "%1$s", "port": %8$d }
                }
                """.formatted(Ports.HOST, layout.fogTempUdp(), layout.fogCo2Udp(), layout.mlCallbackUdp(), CHAIN,
                layout.mlRegistry(), dataDir.resolve("ml-1").toAbsolutePath(), layout.mlRest()),
                MlConfig.class, "ml-1");
    }

    static WebMlConfig web(Layout layout) {
        return new WebMlConfig("web-ml", Ports.HOST, layout.webPort(),
                List.of(new WebMlConfig.Endpoint("ml-1", layout.restUrl())), 2_000);
    }

    static ClientConfig client(Layout layout) {
        return new ClientConfig("clnt-ml", 1_000, 100, 2_000, 2_000,
                List.of(new ClientConfig.EndpointConfig("ml-1", Ports.HOST, layout.mlRegistry(), "MlService")));
    }

    private static CfConfig cf(Layout layout, String id, int port) {
        return CfConfig.builder(id, port)
                .listenHost(Ports.HOST)
                .scan(CfTestPeer.scanConfig(layout.cfFrom(), layout.cfTo()))
                .build();
    }
}
