package at.ac.univie.dse.iot.mlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The DTOs travel through Java serialization (RMI), so they must survive a round trip. */
class DtoSerializationTest {

    private static final Instant AT = Instant.parse("2021-11-20T14:03:05Z");

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void pollResultWithBothEventTypesSurvivesSerialization() throws Exception {
        PredictionDto previous = new PredictionDto("co2", 640, 680, 660, 0.31, 480, "PPM");
        PredictionDto current = new PredictionDto("co2", 680, 720, 700, 0.34, 480, "PPM");
        AnomalyDto anomaly = new AnomalyDto(16, AT, "temperature", "temp-sudden-drop", "Warm room stays warm",
                new PhaseObservationDto("temperature > 20.0", AT.minusSeconds(120), AT.minusSeconds(60), 6),
                new PhaseObservationDto("temperature > 20.0", AT.minusSeconds(60), AT, 6),
                "temperature > 20.0", 14.2, "CELSIUS", AT.plusSeconds(60));
        PollResult result = new PollResult(1637412000000L,
                List.of(anomaly, new PredictionChangeDto(17, AT, "co2", previous, current)), 17, false, false);

        assertEquals(result, roundTrip(result));
    }

    @Test
    void firstPredictionHasNoPrevious() throws Exception {
        PredictionChangeDto change = new PredictionChangeDto(1, AT, "co2", null,
                new PredictionDto("co2", 440, 480, 460, 1.0, 1, "PPM"));

        assertEquals(change, roundTrip(change));
    }

    @Test
    void statusSurvivesSerialization() throws Exception {
        ServiceStatusDto status = new ServiceStatusDto("ml-1", 1, AT, List.of("co2", "temperature"),
                Map.of("fog-temp", "ACTIVE", "fog-co2", "PENDING"), 3, 0, 17);

        assertEquals(status, roundTrip(status));
    }

    @Test
    void collectionsAreCopiedAndImmutable() {
        List<MlEventDto> events = new ArrayList<>();
        PollResult result = new PollResult(1, events, 0, false, false);
        events.add(new PredictionChangeDto(1, AT, "co2", null, new PredictionDto("co2", 0, 1, 0.5, 1, 1, "PPM")));

        assertEquals(0, result.events().size());
        assertThrows(UnsupportedOperationException.class, () -> result.events().clear());
    }
}
