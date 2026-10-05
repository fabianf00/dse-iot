package at.ac.univie.dse.iot.msml;

import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.msml.rest.RestServer;
import at.ac.univie.dse.iot.testsupport.FakeFogServer;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.RestTestClient;
import at.ac.univie.dse.iot.testsupport.Waiting;
import java.rmi.registry.LocateRegistry;
import java.time.Duration;
import java.util.UUID;

/**
 * MS ML in-process with two fake fogs (temperature, CO2) for integration tests: one short
 * behaviour chain ({@code temp-sudden-drop}: P &gt; 20 in [4 s, 2 s], C &gt; 20 in [2 s, 0 s],
 * F &gt; 20 within 2 s), histograms, RMI on a free port and REST on a free port. ML can be
 * restarted on the same database and ports.
 */
public final class MlTestSystem implements AutoCloseable {

    public static final String SERVICE_ID = "ml-test";
    public static final String CHAIN = "temp-sudden-drop";
    private static final long ROUND_MS = 250;

    private final FakeFogServer temperatureFog;
    private final FakeFogServer co2Fog;
    private final MlConfig config;
    private MlService ml;
    private MlRemoteService rmi;
    private RestTestClient rest;

    private MlTestSystem(FakeFogServer temperatureFog, FakeFogServer co2Fog, MlConfig config) {
        this.temperatureFog = temperatureFog;
        this.co2Fog = co2Fog;
        this.config = config;
    }

    /** A JDBC URL of a fresh in-memory database. */
    public static String memoryDatabase() {
        return "jdbc:h2:mem:ml-it-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    /** Starts the fake fogs and ML with the given database. */
    public static MlTestSystem start(String jdbcUrl) throws Exception {
        FakeFogServer temperatureFog = new FakeFogServer("fog-temp", DataType.of("temperature"), Unit.CELSIUS);
        FakeFogServer co2Fog = new FakeFogServer("fog-co2", DataType.of("co2"), Unit.PPM);
        MlConfig config = new ConfigLoader().parse("""
                {
                  "serviceId": "%s",
                  "fogNodes": [
                    { "id": "fog-temp", "host": "127.0.0.1", "port": %d },
                    { "id": "fog-co2", "host": "127.0.0.1", "port": %d }
                  ],
                  "udp": { "bindHost": "127.0.0.1", "callbackPort": %d, "leaseMs": 2000, "subscribeRetryMaxMs": 2000 },
                  "workers": { "threads": 2, "queueCapacity": 10 },
                  "timeSeriesRetentionSeconds": 60,
                  "anomaly": { "minSamplesPerPhase": 2, "chains": [ {
                    "id": "%s", "description": "warm stays warm",
                    "past":    { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 4, "toSec": 2 },
                    "current": { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 2, "toSec": 0 },
                    "future":  { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "withinSec": 2 }
                  } ] },
                  "prediction": { "histograms": [
                    { "dataType": "temperature", "binWidth": 1.0, "maxSamples": 20 },
                    { "dataType": "co2", "binWidth": 50, "maxSamples": 20 }
                  ] },
                  "rmi": { "hostname": "127.0.0.1", "registryPort": %d, "bindName": "MlService" },
                  "storage": { "jdbcUrl": "%s" },
                  "rest": { "host": "127.0.0.1", "port": %d }
                }
                """.formatted(SERVICE_ID, temperatureFog.port(), co2Fog.port(), Ports.freeUdpPort(), CHAIN,
                Ports.freeTcpPort(), jdbcUrl, Ports.freeTcpPort()), MlConfig.class, SERVICE_ID);
        MlTestSystem system = new MlTestSystem(temperatureFog, co2Fog, config);
        system.startMl();
        return system;
    }

    /** Starts (another) ML instance with the same config: same database, ports and fogs. */
    public void startMl() throws Exception {
        ml = new MlService();
        ml.start(config);
        rmi = (MlRemoteService) LocateRegistry.getRegistry("127.0.0.1", config.rmi().registryPort())
                .lookup(config.rmi().bindName());
        rest = new RestTestClient("http://127.0.0.1:" + ml.restPort() + RestServer.BASE_PATH);
    }

    public void stopMl() {
        ml.stop();
    }

    /** Sends one temperature and one CO2 aggregate, then waits a quarter second. */
    public void round(double temperature, double co2) {
        temperatureFog.sendAggregate(temperature);
        co2Fog.sendAggregate(co2);
        Waiting.pause(Duration.ofMillis(ROUND_MS));
    }

    /** Five seconds of normal values (the chain's phases hold), then a temperature drop to 14 °C. */
    public void produceTemperatureDrop() {
        Waiting.waiting().until(() -> rmi.getStatus().fogStatus().values().stream()
                .allMatch(status -> status.startsWith("ACTIVE")));
        for (int i = 0; i < 20; i++) {
            round(21.0 + (i % 5) * 0.2, 600.0);
        }
        round(14.0, 600.0);
    }

    public FakeFogServer temperatureFog() {
        return temperatureFog;
    }

    public FakeFogServer co2Fog() {
        return co2Fog;
    }

    public MlService ml() {
        return ml;
    }

    public MlRemoteService rmi() {
        return rmi;
    }

    public RestTestClient rest() {
        return rest;
    }

    /** Port of the ML REST API. */
    public int restPort() {
        return ml.restPort();
    }

    @Override
    public void close() {
        ml.stop();
        temperatureFog.close();
        co2Fog.close();
    }
}
