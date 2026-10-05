package at.ac.univie.dse.iot.msfog.udp;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fog's UDP socket: a receiver thread answers requests via {@link FogRequestHandler}, and
 * {@link #send} pushes datagrams to subscribers. One socket serves both, so callbacks come from
 * the well-known fog port.
 */
public final class UdpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(UdpServer.class);
    /** Larger than any valid datagram, so oversized requests are detected instead of truncated. */
    private static final int RECEIVE_BUFFER_BYTES = 65_535;

    private final InetSocketAddress bindAddress;
    private final FogRequestHandler handler;
    private final FogMessageCodec codec;
    private final String threadName;
    private DatagramSocket socket;
    private Thread receiver;

    public UdpServer(InetSocketAddress bindAddress, FogRequestHandler handler, FogMessageCodec codec, String threadName) {
        this.bindAddress = bindAddress;
        this.handler = handler;
        this.codec = codec;
        this.threadName = threadName;
    }

    /** Binds the port and starts answering requests. */
    public synchronized void start() throws SocketException {
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(bindAddress);
        receiver = new Thread(this::receiveLoop, threadName);
        receiver.setDaemon(true);
        receiver.start();
        LOG.info("Fog Aggregate API listening on UDP {}", socket.getLocalSocketAddress());
    }

    /** Sends an encoded message; failures are logged, never thrown (UDP is best effort). */
    public void send(byte[] datagram, InetSocketAddress target) {
        DatagramSocket current = socket;
        if (current == null || current.isClosed()) {
            return;
        }
        try {
            current.send(new DatagramPacket(datagram, datagram.length, target));
        } catch (IOException e) {
            LOG.warn("Sending to {} failed: {}", target, e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            socket.close();
        }
        if (receiver != null) {
            try {
                receiver.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    LOG.warn("Receiving failed: {}", e.getMessage());
                }
                continue;
            }
            InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
            try {
                FogMessage answer = handler.handle(packet.getData(), packet.getLength(), source);
                send(codec.encode(answer), source);
            } catch (RuntimeException e) {
                LOG.error("Handling a request from {} failed", source, e);
            }
        }
    }
}
