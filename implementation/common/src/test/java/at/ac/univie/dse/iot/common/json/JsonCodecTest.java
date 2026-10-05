package at.ac.univie.dse.iot.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

    private final JsonCodec codec = JsonCodec.shared();

    private static SensorReading reading() {
        return new SensorReading("iot-temp-1", DataType.of("temperature"), Unit.CELSIUS, 21.37, 1042,
                UUID.fromString("b7e2c1d0-4b1a-4f3e-9c55-0a6f2d9e8a31"), 1637412185120L);
    }

    @Test
    void sensorReadingRoundTrip() {
        SensorReading original = reading();

        SensorReading decoded = codec.fromJson(codec.toJson(original), SensorReading.class);

        assertEquals(original, decoded);
    }

    @Test
    void sensorReadingUsesDocumentedWireFormat() {
        JsonNode json = codec.toTree(reading());

        assertEquals("iot-temp-1", json.get("deviceId").asText());
        assertEquals("temperature", json.get("dataType").asText(), "data type is a plain string");
        assertEquals("CELSIUS", json.get("unit").asText());
        assertEquals(21.37, json.get("value").asDouble());
        assertEquals(1042, json.get("sequence").asLong());
        assertEquals("b7e2c1d0-4b1a-4f3e-9c55-0a6f2d9e8a31", json.get("bootId").asText());
        assertEquals(1637412185120L, json.get("measuredAt").asLong());
        assertEquals(7, json.size());
    }

    @Test
    void emptyAggregateKeepsExplicitNullValueAndNoHelperProperties() {
        AggregatedValue empty = AggregatedValue.empty(
                DataType.of("co2"), Unit.PPM, AggregationMode.MAX, 1000, 2000);

        JsonNode json = codec.toTree(empty);

        assertTrue(json.has("value"));
        assertTrue(json.get("value").isNull());
        assertFalse(json.has("empty"));
        assertFalse(json.has("optionalValue"));
        assertEquals(empty, codec.fromTree(json, AggregatedValue.class));
    }

    @Test
    void aggregateRoundTripThroughBytes() {
        AggregatedValue value = new AggregatedValue(
                DataType.of("temperature"), Unit.CELSIUS, AggregationMode.AVG, 1000, 11000, 28, 3, 21.42);

        byte[] bytes = codec.toBytes(value);

        assertEquals(value, codec.fromBytes(bytes, AggregatedValue.class));
    }

    @Test
    void fromBytesHonoursOffsetAndLength() {
        byte[] json = codec.toBytes(reading());
        byte[] padded = new byte[json.length + 10];
        System.arraycopy(json, 0, padded, 5, json.length);

        assertEquals(reading(), codec.fromBytes(padded, 5, json.length, SensorReading.class));
    }

    @Test
    void unknownPropertiesAreIgnoredForForwardCompatibility() {
        String json = codec.toTree(reading()).toString().replaceFirst("\\{", "{\"addedInV2\":true,");

        assertEquals(reading(), codec.fromJson(json, SensorReading.class));
    }

    @Test
    void malformedJsonIsReportedAsCodecException() {
        byte[] garbage = "{\"garbage\":".getBytes(StandardCharsets.UTF_8);

        assertThrows(JsonCodecException.class, () -> codec.fromBytes(garbage, SensorReading.class));
        assertThrows(JsonCodecException.class, () -> codec.readTree(garbage, 0, garbage.length));
    }

    @Test
    void emptyInputIsReportedAsCodecException() {
        assertThrows(JsonCodecException.class, () -> codec.readTree(""));
    }

    @Test
    void invalidValuesRejectedByRecordValidationAreReportedAsCodecException() {
        String json = codec.toTree(reading()).toString().replace("\"iot-temp-1\"", "\"  \"");

        JsonCodecException e = assertThrows(JsonCodecException.class, () -> codec.fromJson(json, SensorReading.class));
        assertTrue(e.getMessage().contains("SensorReading"), e.getMessage());
    }

    @Test
    void missingPrimitiveIsRejected() {
        String json = "{\"deviceId\":\"d\",\"dataType\":\"co2\",\"unit\":\"PPM\",\"value\":null,"
                + "\"sequence\":1,\"bootId\":\"b7e2c1d0-4b1a-4f3e-9c55-0a6f2d9e8a31\",\"measuredAt\":1}";

        assertThrows(JsonCodecException.class, () -> codec.fromJson(json, SensorReading.class));
    }
}
