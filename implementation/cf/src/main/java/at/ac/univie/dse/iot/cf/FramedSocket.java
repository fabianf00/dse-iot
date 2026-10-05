package at.ac.univie.dse.iot.cf;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;

/**
 * A TCP socket with buffered framed streams. The same instance is used for the handshake and
 * afterwards by the connection, so no bytes buffered during the handshake get lost.
 */
final class FramedSocket implements Closeable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FrameCodec codec;

    FramedSocket(Socket socket, FrameCodec codec) throws IOException {
        this.socket = socket;
        this.codec = codec;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    CfMessage read() throws IOException {
        return codec.read(in);
    }

    /** Writes one frame into the buffer; call {@link #flush()} to send it. */
    void write(CfMessage message) throws IOException {
        codec.write(out, message);
    }

    void flush() throws IOException {
        out.flush();
    }

    void writeAndFlush(CfMessage message) throws IOException {
        write(message);
        flush();
    }

    /** Sets the read timeout; {@link Duration#ZERO} blocks without limit. */
    void setReadTimeout(Duration timeout) throws SocketException {
        socket.setSoTimeout((int) timeout.toMillis());
    }

    InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing left to release.
        }
    }
}
