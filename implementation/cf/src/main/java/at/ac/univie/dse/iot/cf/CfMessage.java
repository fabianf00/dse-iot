package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope of every message on the wire (CF protocol version 1).
 *
 * <pre>{@code
 * {"v":1,"id":"3f7a…","type":"DATA","origin":"iot-temp-1","sender":"iot-co2-2","ttl":4,
 *  "createdAt":1637412185123,"topic":"sensor.temperature","payload":{…}}
 * }</pre>
 *
 * <p>The payload is a JSON tree and must not be modified after the message was created.
 *
 * @param version   protocol version, currently {@value #PROTOCOL_VERSION}
 * @param id        globally unique id set by the origin, used for de-duplication
 * @param type      message type
 * @param origin    node that created the message
 * @param sender    node that sent this copy (last hop), rewritten on every forward
 * @param ttl       remaining hops, at least 1 for {@link MessageType#DATA}, 0 for control messages
 * @param createdAt origin clock in epoch milliseconds, for information only
 * @param topic     topic of a DATA message, e.g. {@code sensor.temperature}; {@code null} for control messages
 * @param payload   application data (DATA) or control data; never {@code null}
 */
@JsonPropertyOrder({"v", "id", "type", "origin", "sender", "ttl", "createdAt", "topic", "payload"})
public record CfMessage(
        @JsonProperty("v") int version,
        UUID id,
        MessageType type,
        NodeId origin,
        NodeId sender,
        int ttl,
        long createdAt,
        String topic,
        JsonNode payload) {

    public static final int PROTOCOL_VERSION = 1;

    public CfMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(sender, "sender");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1 but was " + version);
        }
        if (type == MessageType.DATA) {
            TopicMatcher.requireValidTopic(topic);
            if (ttl < 1) {
                throw new IllegalArgumentException("ttl of a DATA message must be >= 1 but was " + ttl);
            }
        } else if (ttl < 0) {
            throw new IllegalArgumentException("ttl must be >= 0 but was " + ttl);
        }
        if (payload == null || payload.isMissingNode()) {
            payload = JsonNodeFactory.instance.objectNode();
        }
    }

    /** Creates a new DATA message originating at {@code origin}. */
    static CfMessage data(NodeId origin, String topic, JsonNode payload, int ttl, long now) {
        return new CfMessage(PROTOCOL_VERSION, UUID.randomUUID(), MessageType.DATA, origin, origin, ttl, now, topic, payload);
    }

    /** Creates a hop-local control message. */
    static CfMessage control(MessageType type, NodeId self, Object payload, long now) {
        if (!type.isControl()) {
            throw new IllegalArgumentException(type + " is not a control message type");
        }
        JsonNode tree = payload == null ? null : JsonCodec.shared().toTree(payload);
        return new CfMessage(PROTOCOL_VERSION, UUID.randomUUID(), type, self, self, 0, now, null, tree);
    }

    /** The copy that {@code self} forwards: same id and payload, TTL decremented, sender replaced. */
    CfMessage forwardedBy(NodeId self) {
        return new CfMessage(version, id, type, origin, self, ttl - 1, createdAt, topic, payload);
    }

    /**
     * Converts the payload to {@code payloadType}, e.g. {@code message.payloadAs(SensorReading.class)}.
     *
     * @throws at.ac.univie.dse.iot.common.json.JsonCodecException if the payload does not match the type
     */
    public <T> T payloadAs(Class<T> payloadType) {
        return JsonCodec.shared().fromTree(payload, payloadType);
    }
}
