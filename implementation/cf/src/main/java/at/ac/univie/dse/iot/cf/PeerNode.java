package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.cf.ControlPayloads.ByeReason;
import at.ac.univie.dse.iot.cf.ProtocolExceptions.HandshakeRejectedException;
import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CF peer: implementation of {@link CommunicationFramework}.
 *
 * <p>Wires the components together and owns their threads:
 * <ul>
 *   <li>acceptor thread and a small handshake pool for incoming links,</li>
 *   <li>a scheduler for scanning, heartbeats, the rolling refresh and cache cleanup,</li>
 *   <li>a reader and a writer thread per link ({@link PeerConnection}),</li>
 *   <li>one dispatcher thread that calls the MS listeners ({@link MessageDispatcher}).</li>
 * </ul>
 */
final class PeerNode implements CommunicationFramework, PeerConnection.Events {

    private static final Logger LOG = LoggerFactory.getLogger(PeerNode.class);

    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(1);
    private static final int HANDSHAKE_THREADS = 4;
    private static final int PENDING_HANDSHAKES = 64;
    private static final int DISPATCH_QUEUE_CAPACITY = 10_000;
    /** Room for the envelope fields when checking a payload against the frame limit. */
    private static final int ENVELOPE_RESERVE_BYTES = 512;

    private enum State { NEW, RUNNING, CLOSED }

    private final CfConfig config;
    private final NodeId self;
    private final LongSupplier clock = System::currentTimeMillis;
    private final JsonCodec json = JsonCodec.shared();
    private final FrameCodec frameCodec;
    private final ConnectionManager connections;
    private final SubscriptionRegistry subscriptions = new SubscriptionRegistry();
    private final MessageDispatcher dispatcher;
    private final SeenMessageCache seenMessages;
    private final MessageRouter router;
    private final HandshakeHandler handshake;
    private final PeerScanner scanner;
    private final HeartbeatMonitor heartbeat;
    private final PeerRefresher refresher;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private ScheduledExecutorService scheduler;
    private ExecutorService handshakePool;
    private PeerAcceptor acceptor;

    PeerNode(CfConfig config) {
        this.config = config;
        this.self = config.nodeId();
        this.frameCodec = new FrameCodec(json, config.maxFrameBytes());
        this.connections = new ConnectionManager(self, config.maxOutgoingPeers());
        this.dispatcher = new MessageDispatcher(subscriptions, threads("dispatch"), DISPATCH_QUEUE_CAPACITY);
        this.seenMessages = new SeenMessageCache(
                config.seenCache().expiryMs(), config.seenCache().maxEntries(), clock);
        this.router = new MessageRouter(self, config.defaultTtl(), seenMessages, connections, dispatcher, clock);
        CfConfig.ScanConfig scan = config.scan();
        Duration handshakeTimeout = Duration.ofMillis(
                scan == null ? CfConfig.ScanConfig.DEFAULT_HANDSHAKE_TIMEOUT_MS : scan.handshakeTimeoutMs());
        this.handshake = new HandshakeHandler(self, config.advertisedHost(), config.listenPort(),
                handshakeTimeout, connections::admission, clock);
        if (scan != null) {
            ScanStrategy strategy = new RandomRangeScanStrategy(
                    scan.hosts(), scan.portRange().from(), scan.portRange().to(), new SecureRandom());
            this.scanner = new PeerScanner(strategy, connections, this::connectTo,
                    new InetSocketAddress(config.advertisedHost(), config.listenPort()),
                    scan.failedCandidateBackoffMs(), scan.maxAttemptsPerRound(), clock);
            this.refresher = new PeerRefresher(connections, scanner);
        } else {
            this.scanner = null;
            this.refresher = null;
        }
        this.heartbeat = new HeartbeatMonitor(self, connections,
                config.heartbeatIntervalMs(), config.heartbeatTimeoutMs(), clock);
    }

    @Override
    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("CF node " + self + " was already started or closed");
        }
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.listenHost(), config.listenPort()));
        } catch (IOException e) {
            close();
            throw new CfException("CF node " + self + " cannot listen on "
                    + config.listenHost() + ":" + config.listenPort() + ": " + e.getMessage(), e);
        }
        handshakePool = new ThreadPoolExecutor(HANDSHAKE_THREADS, HANDSHAKE_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PENDING_HANDSHAKES), threads("handshake"));
        acceptor = new PeerAcceptor(serverSocket, handshakePool, this::handleIncoming, "cf-" + self + "-acceptor");
        acceptor.start();

        scheduler = Executors.newScheduledThreadPool(2, threads("scheduler"));
        if (scanner != null) {
            schedule("scan", scanner::scanRound, 0, config.scan().intervalMs());
            schedule("refresh", refresher::refresh, config.peerRefreshIntervalMs(), config.peerRefreshIntervalMs());
        } else {
            LOG.warn("CF node {} has no scan config: it accepts peers but does not look for any", self);
        }
        long heartbeatPeriod = Math.max(1, config.heartbeatIntervalMs() / 2);
        schedule("heartbeat", heartbeat::check, heartbeatPeriod, heartbeatPeriod);
        long cleanupPeriod = Math.max(1, config.seenCache().expiryMs() / 2);
        schedule("seen-cache cleanup", seenMessages::evictExpired, cleanupPeriod, cleanupPeriod);

        LOG.info("CF node {} listening on {}:{} (advertised as {}:{})", self,
                config.listenHost(), config.listenPort(), config.advertisedHost(), config.listenPort());
    }

    @Override
    public void publish(String topic, Object payload) {
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException("CF node " + self + " is not running");
        }
        TopicMatcher.requireValidTopic(topic);
        Objects.requireNonNull(payload, "payload");
        JsonNode tree = payload instanceof JsonNode node ? node : json.toTree(payload);
        int payloadBytes = json.toBytes(tree).length;
        if (payloadBytes + ENVELOPE_RESERVE_BYTES > config.maxFrameBytes()) {
            throw new CfException("payload of " + payloadBytes + " bytes is too large, maximum is "
                    + (config.maxFrameBytes() - ENVELOPE_RESERVE_BYTES));
        }
        router.publish(topic, tree);
    }

    @Override
    public Subscription subscribe(String topicPattern, MessageListener listener) {
        if (state.get() == State.CLOSED) {
            throw new IllegalStateException("CF node " + self + " is closed");
        }
        return subscriptions.add(topicPattern, listener);
    }

    @Override
    public NodeId nodeId() {
        return self;
    }

    @Override
    public Set<PeerInfo> connectedPeers() {
        return connections.peerInfos();
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSED);
        if (previous == State.CLOSED) {
            return;
        }
        if (previous == State.RUNNING) {
            stopBackgroundWork();
            List<PeerConnection> open = connections.closeAll();
            open.forEach(connection -> connection.closeGracefully(ByeReason.SHUTDOWN));
            awaitClosed(open);
        }
        dispatcher.close();
        LOG.info("CF node {} closed", self);
    }

    /**
     * Simulates a crash for tests: drops all links without BYE and stops, like a killed process.
     */
    void abort() {
        if (state.getAndSet(State.CLOSED) != State.RUNNING) {
            return;
        }
        stopBackgroundWork();
        List<PeerConnection> open = connections.closeAll();
        open.forEach(connection -> connection.close("aborted"));
        awaitClosed(open);
        dispatcher.close();
    }

    /** Duplicates dropped by the router so far (for tests and diagnostics). */
    long duplicatesDropped() {
        return router.duplicatesDropped();
    }

    /**
     * Connects to {@code candidate} and runs the handshake. Used by the scanner, and by tests to
     * build fixed topologies.
     *
     * @return {@code true} if a new link was established
     */
    boolean connectTo(InetSocketAddress candidate) {
        if (state.get() != State.RUNNING) {
            return false;
        }
        Socket rawSocket = new Socket();
        FramedSocket socket = null;
        try {
            rawSocket.connect(candidate, (int) connectTimeoutMillis());
            socket = new FramedSocket(rawSocket, frameCodec);
            HandshakeHandler.Result result = handshake.initiate(socket, candidate);
            return establish(socket, result, Direction.OUTGOING);
        } catch (HandshakeRejectedException e) {
            LOG.debug("{} rejected our handshake: {}", candidate, e.reason());
        } catch (IOException | RuntimeException e) {
            LOG.debug("No CF peer at {}: {}", candidate, e.getMessage());
        }
        closeQuietly(socket, rawSocket);
        return false;
    }

    @Override
    public void onData(PeerConnection connection, CfMessage message) {
        router.onData(message, connection.remoteId());
    }

    @Override
    public void onClosed(PeerConnection connection, String reason) {
        connections.remove(connection);
    }

    private void handleIncoming(Socket rawSocket) {
        FramedSocket socket = null;
        try {
            socket = new FramedSocket(rawSocket, frameCodec);
            HandshakeHandler.Result result = handshake.accept(socket);
            establish(socket, result, Direction.INCOMING);
            return;
        } catch (HandshakeRejectedException e) {
            LOG.debug("Rejected handshake from {}: {}", rawSocket.getRemoteSocketAddress(), e.reason());
        } catch (IOException | RuntimeException e) {
            LOG.debug("Incoming handshake from {} failed: {}", rawSocket.getRemoteSocketAddress(), e.getMessage());
        }
        closeQuietly(socket, rawSocket);
    }

    /**
     * Registers and starts a link after a successful handshake. A link that may not be registered
     * is closed with BYE.
     *
     * @return {@code true} if the link was registered
     */
    private boolean establish(FramedSocket socket, HandshakeHandler.Result result, Direction direction) {
        PeerConnection connection = new PeerConnection(self, result.remoteId(), result.remoteListenAddress(),
                direction, socket, config.outboundQueueCapacity(), this, clock);
        ConnectionManager.Registration registration = connections.register(connection);
        if (registration == ConnectionManager.Registration.ACCEPTED) {
            connection.start();
            LOG.info("Connected to {} ({}, listening on {})", result.remoteId(), direction, result.remoteListenAddress());
            return true;
        }
        LOG.debug("Dropping new {} link to {}: {}", direction, result.remoteId(), registration);
        connection.closeGracefully(registration == ConnectionManager.Registration.REJECTED_CLOSED
                ? ByeReason.SHUTDOWN : ByeReason.DUPLICATE_CONNECTION);
        return false;
    }

    private void stopBackgroundWork() {
        ExecutorUtils.shutdownGracefully(scheduler, Duration.ofMillis(connectTimeoutMillis() + 1_000));
        if (acceptor != null) {
            acceptor.stop();
        }
        if (handshakePool != null) {
            handshakePool.shutdownNow();
            ExecutorUtils.shutdownGracefully(handshakePool, SHUTDOWN_GRACE);
        }
        try {
            if (acceptor != null) {
                acceptor.awaitStopped(SHUTDOWN_GRACE.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for BYE to be written, then forces the remaining links closed and joins their threads. */
    private void awaitClosed(List<PeerConnection> open) {
        long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
        try {
            for (PeerConnection connection : open) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                connection.awaitClosed(Duration.ofNanos(remaining));
            }
            for (PeerConnection connection : open) {
                connection.close("shutdown");
                connection.awaitThreads(SHUTDOWN_GRACE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            open.forEach(connection -> connection.close("shutdown"));
        }
    }

    private void schedule(String task, Runnable action, long initialDelayMillis, long periodMillis) {
        // An exception would silently cancel a periodic task, so every run is guarded.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                LOG.error("CF task '{}' failed", task, e);
            }
        }, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private long connectTimeoutMillis() {
        return config.scan() == null ? CfConfig.ScanConfig.DEFAULT_CONNECT_TIMEOUT_MS : config.scan().connectTimeoutMs();
    }

    private NamedThreadFactory threads(String purpose) {
        return new NamedThreadFactory("cf-" + self + "-" + purpose, true);
    }

    private static void closeQuietly(FramedSocket socket, Socket rawSocket) {
        if (socket != null) {
            socket.close();
            return;
        }
        try {
            rawSocket.close();
        } catch (IOException e) {
            // Nothing left to release.
        }
    }
}
