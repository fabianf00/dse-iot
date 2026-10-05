package at.ac.univie.dse.iot.cf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import at.ac.univie.dse.iot.cf.ProtocolExceptions.FrameTooLargeException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.MalformedMessageException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.ProtocolViolationException;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    private static final int MAX = 1024;
    private final FrameCodec codec = new FrameCodec(JsonCodec.shared(), MAX);

    private static CfMessage message(String text) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("text", text);
        return CfMessage.data(NodeId.of("a"), "test.topic", payload, 3, 1000);
    }

    @Test
    void framesRoundTrip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        CfMessage first = message("one");
        CfMessage second = message("two");

        codec.write(out, first);
        codec.write(out, second);
        out.flush();

        DataInputStream in = input(bytes.toByteArray());
        assertEquals(first, codec.read(in));
        assertEquals(second, codec.read(in));
        assertThrows(EOFException.class, () -> codec.read(in));
    }

    @Test
    void frameStartsWithBigEndianLength() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CfMessage message = message("x");

        codec.write(new DataOutputStream(bytes), message);

        byte[] frame = bytes.toByteArray();
        int length = ((frame[0] & 0xff) << 24) | ((frame[1] & 0xff) << 16) | ((frame[2] & 0xff) << 8) | (frame[3] & 0xff);
        assertEquals(frame.length - 4, length);
    }

    @Test
    void oversizedMessageIsNotEncoded() {
        CfMessage large = message("x".repeat(MAX));

        assertThrows(FrameTooLargeException.class, () -> codec.encode(large));
    }

    @Test
    void invalidLengthIsAProtocolViolation() {
        assertThrows(ProtocolViolationException.class, () -> codec.read(input(lengthPrefix(MAX + 1))));
        assertThrows(ProtocolViolationException.class, () -> codec.read(input(lengthPrefix(0))));
        assertThrows(ProtocolViolationException.class, () -> codec.read(input(lengthPrefix(-5))));
    }

    @Test
    void truncatedFrameIsEof() {
        byte[] prefix = lengthPrefix(100);
        byte[] truncated = new byte[prefix.length + 10];
        System.arraycopy(prefix, 0, truncated, 0, prefix.length);

        assertThrows(EOFException.class, () -> codec.read(input(truncated)));
    }

    @Test
    void malformedBodyDoesNotDesynchronizeTheStream() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] garbage = "{\"garbage\":".getBytes(StandardCharsets.UTF_8);
        out.writeInt(garbage.length);
        out.write(garbage);
        CfMessage valid = message("after garbage");
        codec.write(out, valid);
        out.flush();

        DataInputStream in = input(bytes.toByteArray());
        assertThrows(MalformedMessageException.class, () -> codec.read(in));
        assertEquals(valid, codec.read(in));
    }

    @Test
    void wellFormedJsonThatIsNotAValidMessageIsMalformed() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        // DATA without topic violates the envelope rules.
        byte[] body = ("{\"v\":1,\"id\":\"3f7a1c52-8a8e-4f7b-9d7e-5b2f8f0c9a11\",\"type\":\"DATA\","
                + "\"origin\":\"a\",\"sender\":\"a\",\"ttl\":2,\"createdAt\":1}").getBytes(StandardCharsets.UTF_8);
        out.writeInt(body.length);
        out.write(body);
        out.flush();

        assertThrows(MalformedMessageException.class, () -> codec.read(input(bytes.toByteArray())));
    }

    private static byte[] lengthPrefix(int length) {
        return new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length};
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
