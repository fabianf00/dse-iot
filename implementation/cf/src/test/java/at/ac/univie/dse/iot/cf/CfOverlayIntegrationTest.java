package at.ac.univie.dse.iot.cf;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.cf.ControlPayloads.Hello;
import at.ac.univie.dse.iot.cf.ControlPayloads.HelloAck;
import at.ac.univie.dse.iot.cf.ControlPayloads.RejectReason;
import at.ac.univie.dse.iot.common.json.JsonCodec;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * IT-CF: real nodes on localhost. The overlay forms, floods, respects TTL, drops duplicates,
 * heals after a crash, and survives misbehaving clients.
 */
class CfOverlayIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String TOPIC = "test.x";

    record Probe(String id, int number) {
    }

    private final List<PeerNode> nodes = new ArrayList<>();
    private final Map<NodeId, Integer> listenPorts = new ConcurrentHashMap<>();
    private final Map<NodeId, Map<UUID, AtomicInteger>> received = new ConcurrentHashMap<>();

    @AfterEach
    void closeNodes() {
        nodes.forEach(PeerNode::close);
    }

    @Test
    void overlayFormsWithinLimitsAndIsConnected() {
        List<PeerNode> overlay = startScanningOverlay(5, 3);

        await().atMost(TIMEOUT).untilAsserted(() -> assertConnected(overlay));

        for (PeerNode node : overlay) {
            Set<PeerInfo> peers = node.connectedPeers();
            long outgoing = peers.stream().filter(p -> p.direction() == Direction.OUTGOING).count();
            assertTrue(outgoing <= 3, node.nodeId() + " has " + outgoing + " outgoing links");
            assertTrue(peers.stream().noneMatch(p -> p.nodeId().equals(node.nodeId())), "no link to itself");
            assertEquals(peers.size(), peers.stream().map(PeerInfo::nodeId).distinct().count(), "one link per peer");
        }
        await().atMost(TIMEOUT).untilAsserted(() -> {
            for (PeerNode node : overlay) {
                for (PeerInfo peer : node.connectedPeers()) {
                    assertTrue(nodeById(overlay, peer.nodeId()).connectedPeers().stream()
                            .anyMatch(back -> back.nodeId().equals(node.nodeId())), "links are symmetric");
                }
            }
        });
    }

    @Test
    void everyMessageIsDeliveredExactlyOnceToEveryOtherNode() throws InterruptedException {
        List<PeerNode> overlay = startScanningOverlay(5, 3);
        overlay.forEach(this::record);
        await().atMost(TIMEOUT).untilAsserted(() -> assertConnected(overlay));
        PeerNode publisher = overlay.get(0);

        for (int i = 0; i < 100; i++) {
            publisher.publish(TOPIC, new Probe("bulk", i));
        }

        for (PeerNode node : overlay.subList(1, overlay.size())) {
            await().atMost(TIMEOUT).until(() -> receivedBy(node).size() == 100);
        }
        Thread.sleep(300);
        for (PeerNode node : overlay.subList(1, overlay.size())) {
            assertEquals(100, receivedBy(node).size());
            assertTrue(receivedBy(node).values().stream().allMatch(count -> count.get() == 1),
                    node.nodeId() + " received a message more than once");
        }
        assertTrue(receivedBy(publisher).isEmpty(), "a node does not receive its own messages");
    }

    @Test
    void duplicatesArrivingOverACycleAreDropped() throws InterruptedException {
        List<PeerNode> triangle = startPassiveNodes(3, 3);
        triangle.forEach(this::record);
        connect(triangle.get(0), triangle.get(1));
        connect(triangle.get(1), triangle.get(2));
        connect(triangle.get(2), triangle.get(0));

        triangle.get(0).publish(TOPIC, new Probe("cycle", 1));

        await().atMost(TIMEOUT).until(() -> receivedBy(triangle.get(1)).size() == 1
                && receivedBy(triangle.get(2)).size() == 1);
        await().atMost(TIMEOUT).until(() -> triangle.get(1).duplicatesDropped() + triangle.get(2).duplicatesDropped() >= 1);
        Thread.sleep(200);
        assertEquals(1, receivedBy(triangle.get(1)).values().iterator().next().get());
        assertEquals(1, receivedBy(triangle.get(2)).values().iterator().next().get());
    }

    @Test
    void ttlLimitsTheNumberOfHops() throws InterruptedException {
        List<PeerNode> line = startPassiveNodes(5, 3);
        line.forEach(this::record);
        for (int i = 0; i < line.size() - 1; i++) {
            connect(line.get(i), line.get(i + 1));
        }

        line.get(0).publish(TOPIC, new Probe("ttl", 1));

        await().atMost(TIMEOUT).until(() -> receivedBy(line.get(3)).size() == 1);
        assertEquals(1, receivedBy(line.get(1)).size());
        assertEquals(1, receivedBy(line.get(2)).size());
        Thread.sleep(300);
        assertTrue(receivedBy(line.get(4)).isEmpty(), "the fourth hop is beyond TTL 3");
    }

    @Test
    void overlayHealsAfterANodeCrashes() {
        List<PeerNode> overlay = startScanningOverlay(5, 6);
        overlay.forEach(this::record);
        await().atMost(TIMEOUT).untilAsserted(() -> assertConnected(overlay));
        PeerNode crashed = overlay.get(2);

        crashed.abort();

        List<PeerNode> survivors = overlay.stream().filter(n -> n != crashed).toList();
        survivors.forEach(this::receivedProbeIds);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            for (PeerNode node : survivors) {
                assertTrue(node.connectedPeers().stream().noneMatch(p -> p.nodeId().equals(crashed.nodeId())));
            }
        });
        AtomicInteger probe = new AtomicInteger();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String id = "after-crash-" + probe.incrementAndGet();
            survivors.get(0).publish(TOPIC, new Probe(id, 0));
            Thread.sleep(100);
            for (PeerNode node : survivors.subList(1, survivors.size())) {
                assertTrue(receivedProbeIds(node).contains(id), node.nodeId() + " missed " + id);
            }
        });
    }

    @Test
    void rollingRefreshReplacesLinksAndKeepsTheOverlayWorking() {
        List<PeerNode> overlay = startScanningOverlay(6, 6, Duration.ofMillis(400));
        overlay.forEach(this::receivedProbeIds);
        await().atMost(TIMEOUT).until(() -> overlay.stream().anyMatch(n -> outgoingLinks(n).size() == 3));
        PeerNode full = overlay.stream().filter(n -> outgoingLinks(n).size() == 3).findFirst().orElseThrow();
        Set<PeerInfo> before = outgoingLinks(full);

        await().atMost(TIMEOUT).until(() -> outgoingLinks(full).stream().noneMatch(before::contains));

        AtomicInteger probe = new AtomicInteger();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String id = "after-refresh-" + probe.incrementAndGet();
            overlay.get(0).publish(TOPIC, new Probe(id, 0));
            Thread.sleep(100);
            for (PeerNode node : overlay.subList(1, overlay.size())) {
                assertTrue(receivedProbeIds(node).contains(id), node.nodeId() + " missed " + id);
            }
        });
    }

    @Test
    void oversizedFrameClosesOnlyTheOffendingConnection() throws IOException {
        List<PeerNode> pair = startPassiveNodes(2, 3);
        connect(pair.get(0), pair.get(1));
        PeerNode target = pair.get(1);

        try (Socket raw = new Socket(HOST, listenPort(target))) {
            raw.setSoTimeout((int) TIMEOUT.toMillis());
            new DataOutputStream(raw.getOutputStream()).writeInt(10 * 1024 * 1024);

            assertEquals(-1, raw.getInputStream().read(), "connection is closed");
        }
        assertEquals(1, target.connectedPeers().size(), "the regular link survives");
    }

    @Test
    void silentPeerGetsPingedAndIsDisconnected() throws IOException {
        PeerNode node = startNode(CfConfig.builder("silent-test", freePorts(1))
                .listenHost(HOST)
                .heartbeat(Duration.ofMillis(100), Duration.ofMillis(500))
                .build());
        FrameCodec codec = new FrameCodec(JsonCodec.shared(), CfConfig.DEFAULT_MAX_FRAME_BYTES);

        try (Socket raw = new Socket(HOST, listenPort(node))) {
            raw.setSoTimeout((int) TIMEOUT.toMillis());
            FramedSocket socket = new FramedSocket(raw, codec);
            socket.writeAndFlush(hello("silent-client"));
            assertTrue(socket.read().payloadAs(HelloAck.class).accepted());

            assertEquals(MessageType.PING, socket.read().type());
            await().atMost(TIMEOUT).until(() -> node.connectedPeers().isEmpty());
            assertEquals(-1, drainUntilClosed(raw.getInputStream()));
        }
    }

    @Test
    void handshakeWithOwnNodeIdIsRejected() throws IOException {
        PeerNode node = startPassiveNodes(1, 3).get(0);
        FrameCodec codec = new FrameCodec(JsonCodec.shared(), CfConfig.DEFAULT_MAX_FRAME_BYTES);

        try (Socket raw = new Socket(HOST, listenPort(node))) {
            raw.setSoTimeout((int) TIMEOUT.toMillis());
            FramedSocket socket = new FramedSocket(raw, codec);
            socket.writeAndFlush(hello(node.nodeId().value()));

            HelloAck ack = socket.read().payloadAs(HelloAck.class);
            assertFalse(ack.accepted());
            assertEquals(RejectReason.SELF_CONNECTION, ack.reason());
        }
        assertTrue(node.connectedPeers().isEmpty());
    }

    @Test
    void secondLinkToAConnectedPeerIsRejected() {
        List<PeerNode> pair = startPassiveNodes(2, 3);
        connect(pair.get(0), pair.get(1));

        assertFalse(pair.get(1).connectTo(new InetSocketAddress(HOST, listenPort(pair.get(0)))));
        assertEquals(1, pair.get(0).connectedPeers().size());
        assertEquals(1, pair.get(1).connectedPeers().size());
    }

    @Test
    void closeSaysGoodbyeAndReleasesAllThreads() {
        List<PeerNode> pair = startPassiveNodes(2, 3);
        connect(pair.get(0), pair.get(1));
        PeerNode closing = pair.get(0);

        closing.close();

        await().atMost(TIMEOUT).until(() -> pair.get(1).connectedPeers().isEmpty());
        await().atMost(TIMEOUT).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("cf-" + closing.nodeId() + "-")));
    }

    @Test
    void apiMisuseIsReported() {
        int port = freePorts(1);
        PeerNode node = new PeerNode(CfConfig.builder("misuse", port).listenHost(HOST).build());
        nodes.add(node);

        assertThrows(IllegalStateException.class, () -> node.publish(TOPIC, new Probe("x", 1)));
        node.start();
        assertThrows(IllegalStateException.class, node::start);
        assertThrows(IllegalArgumentException.class, () -> node.publish("not a topic", new Probe("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> node.subscribe("a.*.b", message -> { }));
        assertThrows(CfException.class, () -> node.publish(TOPIC, new Probe("x".repeat(70_000), 1)));

        PeerNode sameport = new PeerNode(CfConfig.builder("same-port", port).listenHost(HOST).build());
        nodes.add(sameport);
        assertThrows(CfException.class, sameport::start);
    }

    // ---- helpers ----

    private List<PeerNode> startScanningOverlay(int count, int ttl) {
        return startScanningOverlay(count, ttl, Duration.ofMinutes(10));
    }

    private List<PeerNode> startScanningOverlay(int count, int ttl, Duration refreshInterval) {
        int basePort = freePorts(count);
        CfConfig.ScanConfig scan = new CfConfig.ScanConfig(List.of(HOST),
                new CfConfig.PortRange(basePort, basePort + count - 1), 50, 200, 500, 200, 5);
        List<PeerNode> started = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            started.add(startNode(CfConfig.builder("node-" + (char) ('a' + i), basePort + i)
                    .listenHost(HOST)
                    .scan(scan)
                    .defaultTtl(ttl)
                    .heartbeat(Duration.ofMillis(250), Duration.ofMillis(1500))
                    .peerRefreshInterval(refreshInterval)
                    .build()));
        }
        return started;
    }

    private static Set<PeerInfo> outgoingLinks(PeerNode node) {
        return node.connectedPeers().stream()
                .filter(p -> p.direction() == Direction.OUTGOING)
                .collect(Collectors.toSet());
    }

    private List<PeerNode> startPassiveNodes(int count, int ttl) {
        int basePort = freePorts(count);
        List<PeerNode> started = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            started.add(startNode(CfConfig.builder("passive-" + (char) ('a' + i), basePort + i)
                    .listenHost(HOST)
                    .defaultTtl(ttl)
                    .build()));
        }
        return started;
    }

    private PeerNode startNode(CfConfig config) {
        PeerNode node = (PeerNode) CommunicationFrameworkFactory.create(config);
        nodes.add(node);
        listenPorts.put(config.nodeId(), config.listenPort());
        node.start();
        return node;
    }

    private void connect(PeerNode from, PeerNode to) {
        assertTrue(from.connectTo(new InetSocketAddress(HOST, listenPort(to))));
        await().atMost(TIMEOUT).until(() -> to.connectedPeers().stream().anyMatch(p -> p.nodeId().equals(from.nodeId())));
    }

    private void record(PeerNode node) {
        Map<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();
        received.put(node.nodeId(), counts);
        node.subscribe("test.*", message -> counts.computeIfAbsent(message.id(), id -> new AtomicInteger()).incrementAndGet());
    }

    private Map<UUID, AtomicInteger> receivedBy(PeerNode node) {
        return received.get(node.nodeId());
    }

    private final Map<NodeId, Set<String>> probeIds = new ConcurrentHashMap<>();

    private Set<String> receivedProbeIds(PeerNode node) {
        return probeIds.computeIfAbsent(node.nodeId(), id -> {
            Set<String> ids = ConcurrentHashMap.newKeySet();
            node.subscribe(TOPIC, message -> ids.add(message.payloadAs(Probe.class).id()));
            return ids;
        });
    }

    private static void assertConnected(List<PeerNode> overlay) {
        Map<NodeId, Set<NodeId>> graph = new HashMap<>();
        for (PeerNode node : overlay) {
            graph.put(node.nodeId(), node.connectedPeers().stream().map(PeerInfo::nodeId).collect(Collectors.toSet()));
        }
        Set<NodeId> reached = new HashSet<>();
        Deque<NodeId> queue = new ArrayDeque<>(List.of(overlay.get(0).nodeId()));
        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            if (reached.add(current)) {
                queue.addAll(graph.getOrDefault(current, Set.of()));
            }
        }
        assertEquals(graph.keySet(), reached, "overlay is connected: " + graph);
    }

    private static PeerNode nodeById(List<PeerNode> overlay, NodeId id) {
        return overlay.stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }

    private int listenPort(PeerNode node) {
        return listenPorts.get(node.nodeId());
    }

    private static CfMessage hello(String nodeId) {
        return CfMessage.control(MessageType.HELLO, NodeId.of(nodeId),
                new Hello(NodeId.of(nodeId), HOST, 1, ControlPayloads.MAGIC), System.currentTimeMillis());
    }

    private static int drainUntilClosed(InputStream in) throws IOException {
        int read;
        do {
            read = in.read();
        } while (read != -1);
        return read;
    }

    /** Finds {@code count} consecutive free ports on localhost and returns the first. */
    private static int freePorts(int count) {
        for (int attempt = 0; attempt < 50; attempt++) {
            int base = ThreadLocalRandom.current().nextInt(40_000, 60_000);
            if (allFree(base, count)) {
                return base;
            }
        }
        throw new IllegalStateException("no free port range found");
    }

    private static boolean allFree(int base, int count) {
        for (int port = base; port < base + count; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(HOST, port));
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}
