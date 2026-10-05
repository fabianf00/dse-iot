package at.ac.univie.dse.iot.common.fogapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.fogapi.FogMessage.Aggregate;
import at.ac.univie.dse.iot.common.fogapi.FogMessage.ErrorCode;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FogMessageCodecTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");
    private static final UUID REQUEST = UUID.fromString("0b0e8a8e-61a5-4a39-9d3c-1f0f6f2e2a10");

    private final FogMessageCodec codec = new FogMessageCodec();

    @Test
    void everyMessageTypeRoundTrips() throws FogProtocolException {
        List<FogMessage> messages = List.of(
                FogMessage.Subscribe.of("ml-1", null, 50301, 30_000),
                new FogMessage.SubscribeAck(1, REQUEST, "s-1", TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 10_000, 5, 6),
                FogMessage.Renew.of("s-1", 30_000),
                new FogMessage.RenewAck(1, REQUEST, "s-1", 99),
                FogMessage.Unsubscribe.of("s-1"),
                new FogMessage.UnsubscribeAck(1, REQUEST, "s-1"),
                Aggregate.of("fog-temp", 1637412000000L, 57, new AggregatedValue(
                        TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 1637412180000L, 1637412190000L, 28, 3, 21.42)),
                FogMessage.Error.of(REQUEST, ErrorCode.UNKNOWN_SUBSCRIPTION, "no such subscription"));

        for (FogMessage message : messages) {
            assertEquals(message, codec.decode(codec.encode(message)));
        }
    }

    @Test
    void aggregateUsesTheDocumentedWireFormat() {
        Aggregate aggregate = Aggregate.of("fog-temp", 1637412000000L, 57, new AggregatedValue(
                TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 1637412180000L, 1637412190000L, 28, 3, 21.42));

        JsonNode wire = JsonCodec.shared().readTree(new String(codec.encode(aggregate), StandardCharsets.UTF_8));

        assertEquals("AGGREGATE", wire.get("type").asText());
        assertEquals(1, wire.get("v").asInt());
        assertEquals("fog-temp", wire.get("fogId").asText());
        assertEquals(1637412000000L, wire.get("fogEpoch").asLong());
        assertEquals(57, wire.get("sequence").asLong());
        assertEquals("temperature", wire.get("dataType").asText());
        assertEquals("CELSIUS", wire.get("unit").asText());
        assertEquals("AVG", wire.get("aggregationMode").asText());
        assertEquals(28, wire.get("sampleCount").asInt());
        assertEquals(3, wire.get("deviceCount").asInt());
        assertEquals(21.42, wire.get("value").asDouble());
        assertEquals(13, wire.size());
    }

    @Test
    void emptyWindowAggregateHasExplicitNullValue() throws FogProtocolException {
        Aggregate empty = Aggregate.of("fog-co2", 1, 2, AggregatedValue.empty(
                DataType.of("co2"), Unit.PPM, AggregationMode.MAX, 0, 10_000));

        JsonNode wire = JsonCodec.shared().readTree(new String(codec.encode(empty), StandardCharsets.UTF_8));

        assertTrue(wire.get("value").isNull());
        assertTrue(((Aggregate) codec.decode(codec.encode(empty))).toAggregatedValue().isEmpty());
    }

    @Test
    void subscribeWithoutCallbackHostIsAllowed() throws FogProtocolException {
        String json = "{\"v\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\"" + REQUEST
                + "\",\"subscriberId\":\"ml-1\",\"callbackPort\":50301,\"leaseMs\":30000}";

        FogMessage.Subscribe subscribe = (FogMessage.Subscribe) codec.decode(bytes(json));

        assertNull(subscribe.callbackHost());
        assertEquals(50301, subscribe.callbackPort());
    }

    @Test
    void garbageIsMalformedWithoutRequestId() {
        FogProtocolException e = assertThrows(FogProtocolException.class, () -> codec.decode(bytes("{\"garbage\":")));

        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
        assertNull(e.requestId());
    }

    @Test
    void unknownTypeIsMalformedAndKeepsTheRequestId() {
        FogProtocolException e = assertThrows(FogProtocolException.class, () -> codec.decode(bytes(
                "{\"v\":1,\"type\":\"PUBLISH\",\"requestId\":\"" + REQUEST + "\"}")));

        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
        assertEquals(REQUEST, e.requestId());
    }

    @Test
    void invalidFieldIsMalformed() {
        FogProtocolException e = assertThrows(FogProtocolException.class, () -> codec.decode(bytes(
                "{\"v\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\"" + REQUEST
                        + "\",\"subscriberId\":\"ml-1\",\"callbackPort\":70000,\"leaseMs\":30000}")));

        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
        assertTrue(e.getMessage().contains("callbackPort"), e.getMessage());
    }

    @Test
    void otherVersionIsUnsupported() {
        FogProtocolException e = assertThrows(FogProtocolException.class, () -> codec.decode(bytes(
                "{\"v\":2,\"type\":\"SUBSCRIBE\",\"requestId\":\"" + REQUEST + "\"}")));

        assertEquals(ErrorCode.UNSUPPORTED_VERSION, e.code());
        assertEquals(REQUEST, e.requestId());
    }

    @Test
    void missingVersionIsMalformed() {
        FogProtocolException e = assertThrows(FogProtocolException.class,
                () -> codec.decode(bytes("{\"type\":\"RENEW\",\"subscriptionId\":\"s\",\"leaseMs\":1}")));

        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
    }

    @Test
    void oversizedDatagramsAreRejected() {
        byte[] large = new byte[FogMessageCodec.MAX_DATAGRAM_BYTES + 1];

        FogProtocolException e = assertThrows(FogProtocolException.class, () -> codec.decode(large));
        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(FogMessage.Error.of(REQUEST, ErrorCode.MALFORMED_REQUEST, "x".repeat(2000))));
    }

    @Test
    void errorExceptionBecomesErrorMessage() {
        FogProtocolException e = new FogProtocolException(ErrorCode.TOO_MANY_SUBSCRIBERS, REQUEST, "limit 32");

        assertEquals(FogMessage.Error.of(REQUEST, ErrorCode.TOO_MANY_SUBSCRIBERS, "limit 32"), e.toErrorMessage());
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
