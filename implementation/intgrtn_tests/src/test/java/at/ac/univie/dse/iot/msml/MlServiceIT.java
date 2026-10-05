package at.ac.univie.dse.iot.msml;

import static at.ac.univie.dse.iot.testsupport.Waiting.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.MlEventDto;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import at.ac.univie.dse.iot.mlapi.PollResult;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import at.ac.univie.dse.iot.testsupport.FakeFogServer;
import at.ac.univie.dse.iot.testsupport.Ports;
import at.ac.univie.dse.iot.testsupport.Waiting;
import java.rmi.registry.LocateRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IT-ML: subscriptions at two fogs, anomaly detection, prediction changes and RMI polling, a fog
 * restart with a late replay, and a flood of aggregates.
 */
class MlServiceIT {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final DataType CO2 = DataType.of("co2");
    private static final long ROUND_MS = 250;

    private FakeFogServer temperatureFog;
    private FakeFogServer co2Fog;
    private MlService ml;
    private MlRemoteService client;

    @BeforeEach
    void setUp() throws Exception {
        temperatureFog = new FakeFogServer("fog-temp", TEMPERATURE, Unit.CELSIUS);
        co2Fog = new FakeFogServer("fog-co2", CO2, Unit.PPM);
        int registryPort = Ports.freeTcpPort();
        MlConfig config = new ConfigLoader().parse("""
                {
                  "serviceId": "ml-test",
                  "fogNodes": [
                    { "id": "fog-temp", "host": "127.0.0.1", "port": %d },
                    { "id": "fog-co2", "host": "127.0.0.1", "port": %d }
                  ],
                  "udp": { "bindHost": "127.0.0.1", "callbackPort": %d, "leaseMs": 2000, "subscribeRetryMaxMs": 2000 },
                  "workers": { "threads": 2, "queueCapacity": 10 },
                  "timeSeriesRetentionSeconds": 60,
                  "anomaly": { "minSamplesPerPhase": 2, "chains": [ {
                    "id": "temp-sudden-drop", "description": "warm stays warm",
                    "past":    { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 4, "toSec": 2 },
                    "current": { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "fromSec": 2, "toSec": 0 },
                    "future":  { "dataType": "temperature", "condition": { "op": "GT", "value": 20 }, "withinSec": 2 }
                  } ] },
                  "prediction": { "histograms": [
                    { "dataType": "temperature", "binWidth": 1.0, "maxSamples": 20 },
                    { "dataType": "co2", "binWidth": 50, "maxSamples": 20 }
                  ] },
                  "rmi": { "hostname": "127.0.0.1", "registryPort": %d, "bindName": "MlService" },
                  "storage": { "jdbcUrl": "jdbc:h2:mem:ml-it-%s;DB_CLOSE_DELAY=-1" }
                }
                """.formatted(temperatureFog.port(), co2Fog.port(), Ports.freeUdpPort(), registryPort, UUID.randomUUID()),
                MlConfig.class, "ml-test");
        ml = new MlService();
        ml.start(config);
        client = (MlRemoteService) LocateRegistry.getRegistry("127.0.0.1", registryPort).lookup("MlService");
    }

    @AfterEach
    void tearDown() {
        ml.stop();
        temperatureFog.close();
        co2Fog.close();
    }

    @Test
    void detectsAnomaliesAndPredictionChangesAndServesThemOverRmi() throws Exception {
        // 1. ML subscribes at both fogs.
        waiting().until(() -> temperatureFog.hasSubscriber("ml-test") && co2Fog.hasSubscriber("ml-test"));
        waiting().until(() -> client.getStatus().fogStatus().values().stream()
                .allMatch(status -> status.startsWith("ACTIVE")));

        // 2. Normal behaviour: no anomaly; first predictions.
        for (int i = 0; i < 20; i++) {
            round(21.0 + (i % 5) * 0.2, 600.0);
        }
        PollResult first = awaitEvents(0, events -> events.stream()
                .anyMatch(e -> e instanceof PredictionChangeDto p && p.dataType().equals("temperature")));
        assertTrue(first.events().stream().noneMatch(e -> e instanceof AnomalyDto));
        PredictionChangeDto temperaturePrediction = first.events().stream()
                .filter(e -> e instanceof PredictionChangeDto p && p.dataType().equals("temperature"))
                .map(PredictionChangeDto.class::cast).findFirst().orElseThrow();
        assertEquals(21.0, temperaturePrediction.current().binLower());
        assertEquals(22.0, temperaturePrediction.current().binUpper());
        long cursor = first.nextCursor();

        // 3. A sudden drop breaks the behaviour chain.
        round(14.0, 600.0);
        PollResult anomalies = awaitEvents(cursor, events -> events.stream().anyMatch(e -> e instanceof AnomalyDto));
        List<AnomalyDto> found = anomalies.events().stream()
                .filter(AnomalyDto.class::isInstance).map(AnomalyDto.class::cast).toList();
        assertEquals(1, found.size());
        assertEquals("temp-sudden-drop", found.get(0).chainId());
        assertEquals(14.0, found.get(0).observedValue());
        assertEquals("CELSIUS", found.get(0).unit());
        cursor = anomalies.nextCursor();

        // 4. Nothing new happened: an empty page with the same cursor.
        Waiting.pause(Duration.ofMillis(1_000));
        PollResult nothing = client.pollEvents(cursor, 100);
        assertTrue(nothing.events().isEmpty(), nothing.events().toString());
        assertEquals(cursor, nothing.nextCursor());

        // 5. CO2 shifts from 600 to 800: exactly one prediction change, not one per value.
        for (int i = 0; i < 20; i++) {
            co2Fog.sendAggregate(800.0);
            Waiting.pause(Duration.ofMillis(ROUND_MS / 2));
        }
        waiting().until(() -> client.getStatus().queueSize() == 0);
        Waiting.pause(Duration.ofMillis(300));
        List<PredictionChangeDto> co2Changes = co2Changes(cursor);
        assertEquals(1, co2Changes.size(), co2Changes.toString());
        assertEquals(600.0, co2Changes.get(0).previous().binLower());
        assertEquals(800.0, co2Changes.get(0).current().binLower());
        cursor = client.pollEvents(cursor, 100).nextCursor();

        // 6. The CO2 fog restarts: ML subscribes again, ignores a late datagram of the old epoch,
        //    and processes the new stream.
        long oldEpoch = co2Fog.epoch();
        co2Fog.restart();
        waiting().until(() -> co2Fog.hasSubscriber("ml-test"));
        Waiting.pause(Duration.ofMillis(300));
        for (int i = 0; i < 12; i++) {
            co2Fog.sendRaw(oldEpoch, 1_000 + i, 5_000.0);
        }
        for (int i = 0; i < 15; i++) {
            co2Fog.sendAggregate(960.0);
            Waiting.pause(Duration.ofMillis(ROUND_MS / 2));
        }
        waiting().until(() -> client.getStatus().queueSize() == 0);
        Waiting.pause(Duration.ofMillis(300));
        List<PredictionChangeDto> afterRestart = co2Changes(cursor);
        assertEquals(1, afterRestart.size(), afterRestart.toString());
        assertEquals(950.0, afterRestart.get(0).current().binLower(), "old-epoch values (5000) were ignored");

        // 7. A flood of aggregates: the service stays responsive and keeps working.
        for (int i = 0; i < 5_000; i++) {
            temperatureFog.sendAggregate(21.5);
        }
        long before = System.nanoTime();
        ServiceStatusDto status = client.getStatus();
        long millis = (System.nanoTime() - before) / 1_000_000;
        assertTrue(millis < 500, "getStatus took " + millis + " ms");
        assertEquals(List.of("co2", "temperature"), status.dataTypes());
        waiting().until(() -> client.getStatus().queueSize() == 0);
        assertEquals(AbstractMicroService.State.RUNNING, ml.state());
        assertTrue(client.getCurrentPredictions().stream().anyMatch(p -> p.dataType().equals("temperature")));
    }

    @Test
    void invalidPollArgumentsAreReportedToTheClient() {
        try {
            client.pollEvents(-1, 10);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("afterSequence"));
            return;
        } catch (Exception e) {
            throw new AssertionError("expected IllegalArgumentException but got " + e, e);
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    // ---- helpers ----

    private void round(double temperature, double co2) throws InterruptedException {
        temperatureFog.sendAggregate(temperature);
        co2Fog.sendAggregate(co2);
        Waiting.pause(Duration.ofMillis(ROUND_MS));
    }

    private PollResult awaitEvents(long cursor, java.util.function.Predicate<List<MlEventDto>> condition) {
        PollResult[] result = new PollResult[1];
        waiting().until(() -> {
            result[0] = client.pollEvents(cursor, 100);
            return condition.test(result[0].events());
        });
        return result[0];
    }

    private List<PredictionChangeDto> co2Changes(long cursor) throws Exception {
        return client.pollEvents(cursor, 100).events().stream()
                .filter(e -> e instanceof PredictionChangeDto p && p.dataType().equals("co2"))
                .map(PredictionChangeDto.class::cast)
                .toList();
    }
}
