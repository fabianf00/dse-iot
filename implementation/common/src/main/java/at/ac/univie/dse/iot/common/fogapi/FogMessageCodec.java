package at.ac.univie.dse.iot.common.fogapi;

import at.ac.univie.dse.iot.common.fogapi.FogMessage.ErrorCode;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.json.JsonCodecException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Encodes and decodes {@link FogMessage}s as single UDP datagrams of UTF-8 JSON.
 *
 * <p>A datagram is at most {@value #MAX_DATAGRAM_BYTES} bytes, so it fits into one Ethernet or VPN
 * packet without IP fragmentation.
 */
public final class FogMessageCodec {

    public static final int MAX_DATAGRAM_BYTES = 1400;

    private final JsonCodec json;

    public FogMessageCodec() {
        this(JsonCodec.shared());
    }

    public FogMessageCodec(JsonCodec json) {
        this.json = json;
    }

    /**
     * @throws IllegalArgumentException if the message does not fit into one datagram
     */
    public byte[] encode(FogMessage message) {
        byte[] bytes = json.toBytes(message);
        if (bytes.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException(
                    message.getClass().getSimpleName() + " has " + bytes.length + " bytes, maximum is " + MAX_DATAGRAM_BYTES);
        }
        return bytes;
    }

    /**
     * Decodes one datagram.
     *
     * @throws FogProtocolException with the error code to answer, and the request id if it was readable
     */
    public FogMessage decode(byte[] data, int offset, int length) throws FogProtocolException {
        if (length > MAX_DATAGRAM_BYTES) {
            throw new FogProtocolException(ErrorCode.MALFORMED_REQUEST, null,
                    "datagram has " + length + " bytes, maximum is " + MAX_DATAGRAM_BYTES);
        }
        JsonNode tree;
        try {
            tree = json.readTree(data, offset, length);
        } catch (JsonCodecException e) {
            throw new FogProtocolException(ErrorCode.MALFORMED_REQUEST, null, "not valid JSON");
        }
        UUID requestId = requestIdOf(tree);
        if (!tree.isObject()) {
            throw new FogProtocolException(ErrorCode.MALFORMED_REQUEST, null, "expected a JSON object");
        }
        JsonNode version = tree.get("v");
        if (version == null || !version.canConvertToInt()) {
            throw new FogProtocolException(ErrorCode.MALFORMED_REQUEST, requestId, "missing protocol version 'v'");
        }
        if (version.asInt() != FogMessage.VERSION) {
            throw new FogProtocolException(ErrorCode.UNSUPPORTED_VERSION, requestId,
                    "version " + version.asInt() + " is not supported, use " + FogMessage.VERSION);
        }
        try {
            return json.fromTree(tree, FogMessage.class);
        } catch (JsonCodecException e) {
            throw new FogProtocolException(ErrorCode.MALFORMED_REQUEST, requestId, e.getMessage());
        }
    }

    public FogMessage decode(byte[] data) throws FogProtocolException {
        return decode(data, 0, data.length);
    }

    private static UUID requestIdOf(JsonNode tree) {
        JsonNode requestId = tree.get("requestId");
        if (requestId == null || !requestId.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(requestId.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
