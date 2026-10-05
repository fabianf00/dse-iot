package at.ac.univie.dse.iot.cf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.ControlPayloads.Bye;
import at.ac.univie.dse.iot.cf.ControlPayloads.ByeReason;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.json.JsonCodecException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CfMessageTest {

    record Reading(String deviceId, double value) {
    }

    private static final NodeId A = NodeId.of("iot-temp-1");
    private static final NodeId B = NodeId.of("iot-co2-2");
    private final JsonCodec json = JsonCodec.shared();

    @Test
    void dataMessageUsesDocumentedWireFormat() {
        CfMessage message = CfMessage.data(A, "sensor.temperature", json.toTree(new Reading("iot-temp-1", 21.5)), 6, 1637412185123L);

        JsonNode wire = json.readTree(json.toJson(message));

        assertEquals(List.of("v", "id", "type", "origin", "sender", "ttl", "createdAt", "topic", "payload"),
                iteratorToList(wire.fieldNames()));
        assertEquals(1, wire.get("v").asInt());
        assertEquals("DATA", wire.get("type").asText());
        assertEquals("iot-temp-1", wire.get("origin").asText());
        assertEquals("iot-temp-1", wire.get("sender").asText());
        assertEquals(6, wire.get("ttl").asInt());
        assertEquals("sensor.temperature", wire.get("topic").asText());
        assertEquals(21.5, wire.get("payload").get("value").asDouble());
        assertEquals(message, json.fromJson(json.toJson(message), CfMessage.class));
    }

    @Test
    void forwardingDecrementsTtlAndReplacesSender() {
        CfMessage original = CfMessage.data(A, "t", json.toTree(new Reading("d", 1)), 4, 1);

        CfMessage forwarded = original.forwardedBy(B);

        assertEquals(original.id(), forwarded.id());
        assertEquals(A, forwarded.origin());
        assertEquals(B, forwarded.sender());
        assertEquals(3, forwarded.ttl());
        assertEquals(original.payload(), forwarded.payload());
    }

    @Test
    void payloadConvertsToTypedObject() {
        CfMessage message = CfMessage.data(A, "t", json.toTree(new Reading("d", 2.5)), 1, 1);

        assertEquals(new Reading("d", 2.5), message.payloadAs(Reading.class));
        assertThrows(JsonCodecException.class, () -> message.payloadAs(Integer.class));
    }

    @Test
    void controlMessagesHaveNoTopicAndZeroTtl() {
        CfMessage bye = CfMessage.control(MessageType.BYE, A, new Bye(ByeReason.REFRESH), 1);

        assertNull(bye.topic());
        assertEquals(0, bye.ttl());
        assertEquals(new Bye(ByeReason.REFRESH), bye.payloadAs(Bye.class));
        assertTrue(CfMessage.control(MessageType.PING, A, null, 1).payload().isObject());
        assertThrows(IllegalArgumentException.class, () -> CfMessage.control(MessageType.DATA, A, null, 1));
    }

    @Test
    void invalidEnvelopesAreRejected() {
        UUID id = UUID.randomUUID();
        JsonNode payload = json.toTree(new Reading("d", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CfMessage(1, id, MessageType.DATA, A, A, 0, 1, "t", payload), "DATA with TTL 0");
        assertThrows(IllegalArgumentException.class,
                () -> new CfMessage(1, id, MessageType.DATA, A, A, 1, 1, null, payload), "DATA without topic");
        assertThrows(IllegalArgumentException.class,
                () -> new CfMessage(1, id, MessageType.DATA, A, A, 1, 1, "bad topic", payload), "invalid topic");
        assertThrows(IllegalArgumentException.class,
                () -> new CfMessage(0, id, MessageType.PING, A, A, 0, 1, null, null), "version 0");
        assertThrows(NullPointerException.class,
                () -> new CfMessage(1, null, MessageType.PING, A, A, 0, 1, null, null), "no id");
    }

    @Test
    void nodeIdsAreValidated() {
        assertEquals("fog-temp", NodeId.of("fog-temp").toString());
        assertThrows(IllegalArgumentException.class, () -> NodeId.of(""));
        assertThrows(IllegalArgumentException.class, () -> NodeId.of("has space"));
        assertThrows(IllegalArgumentException.class, () -> NodeId.of("x".repeat(65)));
    }

    private static List<String> iteratorToList(java.util.Iterator<String> iterator) {
        List<String> list = new java.util.ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }
}
