package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.cf.ControlPayloads.Bye;
import at.ac.univie.dse.iot.cf.ControlPayloads.ByeReason;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.FrameTooLargeException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.MalformedMessageException;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.ProtocolViolationException;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One established link to a peer, after a successful handshake.
 *
 * <p>A reader thread blocks on the socket and handles control messages itself (PING, PONG,
 * BYE); DATA messages go to {@link Events#onData}. A writer thread drains a bounded outbound
 * queue, so a slow peer never blocks the sender. When the queue is full, the oldest message is
 * dropped.
 */
final class PeerConnection {

    private static final Logger LOG = LoggerFactory.getLogger(PeerConnection.class);

    /** Malformed or unexpected messages tolerated before the link is closed. */
    static final int MAX_PROTOCOL_ERRORS = 3;

    /** Callbacks to the owner of the connection. Called on the connection's threads. */
    interface Events {
        void onData(PeerConnection connection, CfMessage message);

        void onClosed(PeerConnection connection, String reason);
    }

    private final NodeId self;
    private final NodeId remoteId;
    private final InetSocketAddress remoteListenAddress;
    private final Direction direction;
    private final FramedSocket socket;
    private final BlockingQueue<CfMessage> outbound;
    private final Events events;
    private final LongSupplier clock;
    private final Instant connectedSince;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final AtomicLong droppedMessages = new AtomicLong();
    private volatile long lastReceivedAt;
    private volatile long lastSentAt;
    private volatile long closingSince;
    private volatile Thread reader;
    private volatile Thread writer;
    private int protocolErrors;

    PeerConnection(NodeId self, NodeId remoteId, InetSocketAddress remoteListenAddress, Direction direction,
            FramedSocket socket, int queueCapacity, Events events, LongSupplier clock) {
        this.self = self;
        this.remoteId = remoteId;
        this.remoteListenAddress = remoteListenAddress;
        this.direction = direction;
        this.socket = socket;
        this.outbound = new LinkedBlockingQueue<>(queueCapacity);
        this.events = events;
        this.clock = clock;
        this.connectedSince = Instant.ofEpochMilli(clock.getAsLong());
        this.lastReceivedAt = clock.getAsLong();
        this.lastSentAt = clock.getAsLong();
    }

    /** Starts the reader and writer threads. */
    void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        String prefix = "cf-" + self + "-";
        Thread readerThread = new Thread(this::readLoop, prefix + "reader-" + remoteId);
        Thread writerThread = new Thread(this::writeLoop, prefix + "writer-" + remoteId);
        readerThread.setDaemon(true);
        writerThread.setDaemon(true);
        reader = readerThread;
        writer = writerThread;
        readerThread.start();
        writerThread.start();
    }

    /**
     * Queues a message for sending. Never blocks: if the queue is full the oldest queued message
     * is dropped.
     *
     * @return {@code false} if the link is closing or closed
     */
    boolean send(CfMessage message) {
        if (closing.get() || closed.get()) {
            return false;
        }
        enqueue(message);
        return true;
    }

    /**
     * Sends BYE and closes the link once it is written. Pending messages are discarded. If the
     * connection was never started, BYE is written right away on the calling thread.
     */
    void closeGracefully(ByeReason reason) {
        if (closed.get() || !closing.compareAndSet(false, true)) {
            return;
        }
        closingSince = clock.getAsLong();
        CfMessage bye = CfMessage.control(MessageType.BYE, self, new Bye(reason), clock.getAsLong());
        if (!started.get()) {
            try {
                socket.writeAndFlush(bye);
            } catch (IOException | RuntimeException e) {
                LOG.debug("Could not say goodbye to {}: {}", remoteId, e.getMessage());
            }
            close("closed locally (" + reason + ")");
            return;
        }
        outbound.clear();
        enqueue(bye);
    }

    /** Closes the link at once, without BYE. Calling it again is harmless. */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        socket.close();
        Thread writerThread = writer;
        if (writerThread != null && writerThread != Thread.currentThread()) {
            writerThread.interrupt();
        }
        LOG.info("Disconnected from {} ({}): {}", remoteId, direction, reason);
        closedLatch.countDown();
        events.onClosed(this, reason);
    }

    /** Waits until the link is closed. */
    boolean awaitClosed(Duration timeout) throws InterruptedException {
        return closedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits until the reader and writer threads have finished. */
    void awaitThreads(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : new Thread[] {reader, writer}) {
            if (thread != null && thread != Thread.currentThread()) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                thread.join(Math.max(1, remainingMillis));
            }
        }
    }

    NodeId remoteId() {
        return remoteId;
    }

    InetSocketAddress remoteListenAddress() {
        return remoteListenAddress;
    }

    Direction direction() {
        return direction;
    }

    Instant connectedSince() {
        return connectedSince;
    }

    long lastReceivedAt() {
        return lastReceivedAt;
    }

    long lastSentAt() {
        return lastSentAt;
    }

    boolean isClosed() {
        return closed.get();
    }

    /** Whether BYE was queued before {@code now - timeoutMillis} but the link is still open. */
    boolean isClosingLongerThan(long timeoutMillis, long now) {
        return closing.get() && !closed.get() && now - closingSince > timeoutMillis;
    }

    long droppedMessages() {
        return droppedMessages.get();
    }

    PeerInfo info() {
        return new PeerInfo(remoteId, remoteListenAddress, direction, connectedSince);
    }

    private void enqueue(CfMessage message) {
        while (!outbound.offer(message)) {
            if (outbound.poll() != null) {
                long count = droppedMessages.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    LOG.warn("Link to {} is too slow, dropped {} queued messages so far", remoteId, count);
                }
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                CfMessage message;
                try {
                    message = socket.read();
                } catch (MalformedMessageException e) {
                    onProtocolError("malformed message: " + e.getMessage());
                    continue;
                }
                lastReceivedAt = clock.getAsLong();
                handle(message);
            }
        } catch (ProtocolViolationException e) {
            close("protocol violation: " + e.getMessage());
        } catch (EOFException e) {
            close("connection closed by peer");
        } catch (IOException e) {
            close(closed.get() ? "closed" : "read failed: " + e.getMessage());
        }
    }

    private void handle(CfMessage message) {
        switch (message.type()) {
            case DATA -> {
                try {
                    events.onData(this, message);
                } catch (RuntimeException e) {
                    LOG.error("Failed to route message {} from {}", message.id(), remoteId, e);
                }
            }
            case PING -> send(CfMessage.control(MessageType.PONG, self, null, clock.getAsLong()));
            case PONG -> {
                // lastReceivedAt is already updated; nothing else to do.
            }
            case BYE -> close("peer said goodbye (" + byeReasonOf(message) + ")");
            case HELLO, HELLO_ACK -> onProtocolError("unexpected " + message.type() + " after the handshake");
        }
    }

    private void onProtocolError(String problem) {
        protocolErrors++;
        LOG.warn("Protocol error {} of {} from {}: {}", protocolErrors, MAX_PROTOCOL_ERRORS, remoteId, problem);
        if (protocolErrors >= MAX_PROTOCOL_ERRORS) {
            closeGracefully(ByeReason.PROTOCOL_ERROR);
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                CfMessage message = outbound.poll();
                if (message == null) {
                    // Flush before blocking, so nothing stays in the buffer while the queue is idle.
                    socket.flush();
                    message = outbound.take();
                }
                try {
                    socket.write(message);
                } catch (FrameTooLargeException e) {
                    LOG.warn("Dropped message to {}: {}", remoteId, e.getMessage());
                    continue;
                }
                lastSentAt = clock.getAsLong();
                if (message.type() == MessageType.BYE) {
                    socket.flush();
                    close("closed locally (" + byeReasonOf(message) + ")");
                    return;
                }
            }
        } catch (InterruptedException e) {
            // close() interrupts the writer; the link is already closed.
        } catch (IOException e) {
            close(closed.get() ? "closed" : "write failed: " + e.getMessage());
        }
    }

    private static String byeReasonOf(CfMessage bye) {
        try {
            Bye payload = bye.payloadAs(Bye.class);
            return payload.reason() == null ? "no reason" : payload.reason().name();
        } catch (RuntimeException e) {
            return "unknown reason";
        }
    }
}
