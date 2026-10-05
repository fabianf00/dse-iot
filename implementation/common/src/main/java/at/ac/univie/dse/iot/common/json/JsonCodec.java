package at.ac.univie.dse.iot.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Converts objects to and from JSON with one consistently configured Jackson mapper.
 *
 * <p>The codec is a tolerant reader: unknown properties are ignored, so a newer peer can add
 * fields without breaking older ones. All failures are reported as {@link JsonCodecException}.
 * Instances are thread-safe.
 */
public final class JsonCodec {

    private static final JsonCodec SHARED = new JsonCodec(createDefaultMapper());

    private final ObjectMapper mapper;

    private JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Returns the codec shared by all components of a process. */
    public static JsonCodec shared() {
        return SHARED;
    }

    private static ObjectMapper createDefaultMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Returns a copy of the underlying mapper. Changes to the copy do not affect this codec.
     */
    public ObjectMapper copyMapper() {
        return mapper.copy();
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Cannot serialize " + typeName(value) + ": " + e.getOriginalMessage(), e);
        }
    }

    /** Serializes {@code value} to UTF-8 encoded JSON. */
    public byte[] toBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Cannot serialize " + typeName(value) + ": " + e.getOriginalMessage(), e);
        }
    }

    public JsonNode toTree(Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (IllegalArgumentException e) {
            throw new JsonCodecException("Cannot convert " + typeName(value) + " to a JSON tree: " + e.getMessage(), e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Cannot read " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /** Deserializes {@code length} bytes of UTF-8 JSON starting at {@code offset}. */
    public <T> T fromBytes(byte[] bytes, int offset, int length, Class<T> type) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return mapper.readValue(bytes, offset, length, type);
        } catch (IOException e) {
            throw new JsonCodecException("Cannot read " + type.getSimpleName() + ": " + originalMessage(e), e);
        }
    }

    public <T> T fromBytes(byte[] bytes, Class<T> type) {
        return fromBytes(bytes, 0, bytes.length, type);
    }

    public JsonNode readTree(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            JsonNode node = mapper.readTree(bytes, offset, length);
            if (node == null || node.isMissingNode()) {
                throw new JsonCodecException("Cannot read JSON: no content");
            }
            return node;
        } catch (IOException e) {
            throw new JsonCodecException("Cannot read JSON: " + originalMessage(e), e);
        }
    }

    public JsonNode readTree(String json) {
        byte[] bytes = Objects.requireNonNull(json, "json").getBytes(StandardCharsets.UTF_8);
        return readTree(bytes, 0, bytes.length);
    }

    public <T> T fromTree(JsonNode node, Class<T> type) {
        Objects.requireNonNull(node, "node");
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("Cannot read " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new JsonCodecException("Cannot read " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static String originalMessage(IOException e) {
        return e instanceof JsonProcessingException jpe ? jpe.getOriginalMessage() : e.getMessage();
    }
}
