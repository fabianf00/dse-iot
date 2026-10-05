package at.ac.univie.dse.iot.testsupport;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.fogapi.FogProtocolException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client of the Fog Aggregate API for tests. Keeps answers (ACKs, errors) and pushed aggregates in
 * separate queues, so a test can wait for one without consuming the other.
 */
public final class UdpFogTestClient implements AutoCloseable {

    private final FogMessageCodec codec = new FogMessageCodec();
    private final DatagramSocket socket;
    private final InetSocketAddress fog;
    private final BlockingQueue<FogMessage> answers = new LinkedBlockingQueue<>();
    private final BlockingQueue<FogMessage.Aggregate> aggregates = new LinkedBlockingQueue<>();

    public UdpFogTestClient(InetSocketAddress fog) {
        this.fog = fog;
        try {
            this.socket = new DatagramSocket(new InetSocketAddress(Ports.HOST, 0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread receiver = new Thread(this::receive, "udp-fog-test-client");
        receiver.setDaemon(true);
        receiver.start();
    }

    /** Local port, to be used as {@code callbackPort}. */
    public int port() {
        return socket.getLocalPort();
    }

    /** Sends a request and waits for the next answer, which must be of {@code answerType}. */
    public <T extends FogMessage> T request(FogMessage request, Class<T> answerType) {
        sendRaw(codec.encode(request));
        FogMessage answer = nextAnswer();
        if (!answerType.isInstance(answer)) {
            throw new AssertionError("expected " + answerType.getSimpleName() + " but got " + answer);
        }
        return answerType.cast(answer);
    }

    public void sendRaw(byte[] datagram) {
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, fog));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The next answer; fails after {@link Waiting#TIMEOUT}. */
    public FogMessage nextAnswer() {
        return poll(answers, Waiting.TIMEOUT, "answer");
    }

    /** The next pushed aggregate; fails after {@link Waiting#TIMEOUT}. */
    public FogMessage.Aggregate nextAggregate() {
        return poll(aggregates, Waiting.TIMEOUT, "aggregate");
    }

    /** The next aggregate, or {@code null} if none arrives within {@code timeout}. */
    public FogMessage.Aggregate pollAggregate(Duration timeout) {
        try {
            return aggregates.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public void clearAggregates() {
        aggregates.clear();
    }

    @Override
    public void close() {
        socket.close();
    }

    private static <T> T poll(BlockingQueue<T> queue, Duration timeout, String what) {
        try {
            T value = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (value == null) {
                throw new AssertionError("no " + what + " from the fog within " + timeout.toSeconds() + " s");
            }
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void receive() {
        byte[] buffer = new byte[65_535];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                FogMessage message = codec.decode(packet.getData(), 0, packet.getLength());
                if (message instanceof FogMessage.Aggregate aggregate) {
                    aggregates.add(aggregate);
                } else {
                    answers.add(message);
                }
            } catch (IOException | FogProtocolException e) {
                // Socket closed, or a datagram the test does not care about.
            }
        }
    }
}
