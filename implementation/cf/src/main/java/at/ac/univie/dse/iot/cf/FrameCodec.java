package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.cf.ProtocolExceptions.FrameTooLargeException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.MalformedMessageException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.ProtocolViolationException;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.json.JsonCodecException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Frames messages on a TCP stream: a 4-byte big-endian length followed by that many bytes of
 * UTF-8 JSON ({@link CfMessage}).
 */
final class FrameCodec {

    private final JsonCodec json;
    private final int maxFrameBytes;

    FrameCodec(JsonCodec json, int maxFrameBytes) {
        this.json = json;
        this.maxFrameBytes = maxFrameBytes;
    }

    int maxFrameBytes() {
        return maxFrameBytes;
    }

    /** Encodes the frame body. */
    byte[] encode(CfMessage message) {
        byte[] body = json.toBytes(message);
        if (body.length > maxFrameBytes) {
            throw new FrameTooLargeException(
                    "message " + message.id() + " has " + body.length + " bytes, maximum is " + maxFrameBytes);
        }
        return body;
    }

    /** Writes one frame without flushing. */
    void write(DataOutputStream out, CfMessage message) throws IOException {
        byte[] body = encode(message);
        out.writeInt(body.length);
        out.write(body);
    }

    /**
     * Reads one frame.
     *
     * @throws java.io.EOFException      if the stream ended (peer closed the connection)
     * @throws ProtocolViolationException if the length is invalid; the stream is out of sync
     * @throws MalformedMessageException  if the frame is not a valid message; the next frame can be read
     */
    CfMessage read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > maxFrameBytes) {
            throw new ProtocolViolationException("invalid frame length " + length + " (maximum " + maxFrameBytes + ")");
        }
        byte[] body = new byte[length];
        in.readFully(body);
        try {
            return json.fromBytes(body, CfMessage.class);
        } catch (JsonCodecException e) {
            throw new MalformedMessageException(e.getMessage(), e);
        }
    }
}
