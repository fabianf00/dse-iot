package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.cf.ControlPayloads.Hello;
import at.ac.univie.dse.iot.cf.ControlPayloads.HelloAck;
import at.ac.univie.dse.iot.cf.ControlPayloads.RejectReason;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.HandshakeRejectedException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.ProtocolViolationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Runs the HELLO / HELLO_ACK exchange that turns a TCP connection into a CF link.
 *
 * <p>The handshake identifies the remote node, proves that a scanned port belongs to a CF peer
 * ({@link ControlPayloads#MAGIC}), and rejects links to the node itself and second links to an
 * already connected node.
 */
final class HandshakeHandler {

    /** Result of a successful handshake. */
    record Result(NodeId remoteId, InetSocketAddress remoteListenAddress) {
    }

    private final NodeId self;
    private final String advertisedHost;
    private final int listenPort;
    private final Duration timeout;
    private final Function<NodeId, Optional<RejectReason>> admission;
    private final LongSupplier clock;

    /**
     * @param admission decides whether a remote node may connect; empty means accepted
     */
    HandshakeHandler(NodeId self, String advertisedHost, int listenPort, Duration timeout,
            Function<NodeId, Optional<RejectReason>> admission, LongSupplier clock) {
        this.self = self;
        this.advertisedHost = advertisedHost;
        this.listenPort = listenPort;
        this.timeout = timeout;
        this.admission = admission;
        this.clock = clock;
    }

    /** Connecting side: sends HELLO and waits for an accepting HELLO_ACK. */
    Result initiate(FramedSocket socket, InetSocketAddress target) throws IOException {
        socket.setReadTimeout(timeout);
        socket.writeAndFlush(CfMessage.control(MessageType.HELLO, self,
                new Hello(self, advertisedHost, listenPort, ControlPayloads.MAGIC), clock.getAsLong()));
        CfMessage reply = socket.read();
        if (reply.type() != MessageType.HELLO_ACK) {
            throw new ProtocolViolationException("expected HELLO_ACK but got " + reply.type());
        }
        HelloAck ack = payload(reply, HelloAck.class);
        if (!ack.accepted()) {
            throw new HandshakeRejectedException(ack.reason());
        }
        if (ack.nodeId() == null) {
            throw new ProtocolViolationException("HELLO_ACK without nodeId");
        }
        if (ack.nodeId().equals(self)) {
            throw new HandshakeRejectedException(RejectReason.SELF_CONNECTION);
        }
        socket.setReadTimeout(Duration.ZERO);
        return new Result(ack.nodeId(), target);
    }

    /** Accepting side: waits for HELLO and answers with HELLO_ACK. */
    Result accept(FramedSocket socket) throws IOException {
        socket.setReadTimeout(timeout);
        CfMessage message = socket.read();
        if (message.type() != MessageType.HELLO) {
            throw new ProtocolViolationException("expected HELLO but got " + message.type());
        }
        Hello hello = payload(message, Hello.class);
        if (!ControlPayloads.MAGIC.equals(hello.magic()) || hello.nodeId() == null) {
            throw new ProtocolViolationException("HELLO without valid magic or nodeId");
        }
        if (message.version() != CfMessage.PROTOCOL_VERSION) {
            reject(socket, RejectReason.UNSUPPORTED_VERSION);
        }
        if (hello.nodeId().equals(self)) {
            reject(socket, RejectReason.SELF_CONNECTION);
        }
        Optional<RejectReason> rejection = admission.apply(hello.nodeId());
        if (rejection.isPresent()) {
            reject(socket, rejection.get());
        }
        socket.writeAndFlush(CfMessage.control(MessageType.HELLO_ACK, self,
                new HelloAck(self, true, null), clock.getAsLong()));
        socket.setReadTimeout(Duration.ZERO);
        return new Result(hello.nodeId(), listenAddressOf(hello, socket));
    }

    private void reject(FramedSocket socket, RejectReason reason) throws IOException {
        socket.writeAndFlush(CfMessage.control(MessageType.HELLO_ACK, self,
                new HelloAck(self, false, reason), clock.getAsLong()));
        throw new HandshakeRejectedException(reason);
    }

    /** Address the remote accepts connections on; falls back to its source IP. */
    private static InetSocketAddress listenAddressOf(Hello hello, FramedSocket socket) {
        if (hello.advertisedHost() != null && !hello.advertisedHost().isBlank()) {
            InetSocketAddress advertised = new InetSocketAddress(hello.advertisedHost(), hello.listenPort());
            if (!advertised.isUnresolved()) {
                return advertised;
            }
        }
        return new InetSocketAddress(socket.remoteAddress().getAddress(), hello.listenPort());
    }

    private static <T> T payload(CfMessage message, Class<T> type) throws ProtocolViolationException {
        try {
            return message.payloadAs(type);
        } catch (RuntimeException e) {
            throw new ProtocolViolationException("invalid " + message.type() + " payload: " + e.getMessage());
        }
    }
}
