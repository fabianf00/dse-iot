package at.ac.univie.dse.iot.cf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests of peer discovery and the duplicate-link rule. */
class DiscoveryTest {

    private static final NodeId A = NodeId.of("a");
    private static final NodeId B = NodeId.of("b");

    @Test
    void scanStrategyReturnsOnlyAddressesInRange() {
        RandomRangeScanStrategy strategy = new RandomRangeScanStrategy(List.of("127.0.0.1"), 50100, 50104, new Random(1));

        for (int i = 0; i < 50; i++) {
            InetSocketAddress candidate = strategy.nextCandidate(Set.of()).orElseThrow();
            assertEquals("127.0.0.1", candidate.getAddress().getHostAddress());
            assertTrue(candidate.getPort() >= 50100 && candidate.getPort() <= 50104);
        }
    }

    @Test
    void scanStrategyNeverReturnsExcludedAddressesAndCoversTheRest() {
        RandomRangeScanStrategy strategy = new RandomRangeScanStrategy(List.of("127.0.0.1"), 50100, 50104, new Random(7));
        Set<InetSocketAddress> excluded = Set.of(
                new InetSocketAddress("127.0.0.1", 50100), new InetSocketAddress("127.0.0.1", 50102));

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(strategy.nextCandidate(excluded).orElseThrow().getPort());
        }

        assertEquals(Set.of(50101, 50103, 50104), seen);
    }

    @Test
    void scanStrategyReturnsNothingWhenEverythingIsExcluded() {
        RandomRangeScanStrategy strategy = new RandomRangeScanStrategy(List.of("127.0.0.1"), 50100, 50101, new Random(1));

        Optional<InetSocketAddress> candidate = strategy.nextCandidate(Set.of(
                new InetSocketAddress("127.0.0.1", 50100), new InetSocketAddress("127.0.0.1", 50101)));

        assertTrue(candidate.isEmpty());
    }

    @Test
    void scanStrategySkipsUnresolvableHosts() {
        RandomRangeScanStrategy strategy = new RandomRangeScanStrategy(
                List.of("host.invalid", "127.0.0.1"), 50100, 50100, new Random(1));

        assertEquals(new InetSocketAddress("127.0.0.1", 50100), strategy.nextCandidate(Set.of()).orElseThrow());
    }

    @Test
    void scannerSkipsFailedCandidatesDuringBackoff() {
        ConnectionManager connections = new ConnectionManager(A, 3);
        InetSocketAddress self = new InetSocketAddress("127.0.0.1", 50100);
        InetSocketAddress other = new InetSocketAddress("127.0.0.1", 50101);
        long[] now = {0};
        List<InetSocketAddress> attempts = new java.util.ArrayList<>();
        PeerScanner scanner = new PeerScanner(
                new RandomRangeScanStrategy(List.of("127.0.0.1"), 50100, 50101, new Random(1)),
                connections, candidate -> attempts.add(candidate) && false, self, 1_000, 5, () -> now[0]);

        scanner.scanRound();
        assertEquals(List.of(other), attempts, "own address is never scanned; failed candidate backs off");

        now[0] = 500;
        scanner.scanRound();
        assertEquals(1, attempts.size());

        now[0] = 1_000;
        scanner.scanRound();
        assertEquals(2, attempts.size());
    }

    @Test
    void bothSidesKeepTheLinkOpenedByTheSmallerNodeId() {
        // A < B. A link opened by A is OUTGOING at A and INCOMING at B.
        // At A: existing = A's own link (OUTGOING), candidate = B's link (INCOMING) -> keep existing.
        assertFalse(ConnectionManager.keepCandidate(A, B, Direction.OUTGOING, Direction.INCOMING));
        // At B: existing = B's own link (OUTGOING), candidate = A's link (INCOMING) -> keep candidate.
        assertTrue(ConnectionManager.keepCandidate(B, A, Direction.OUTGOING, Direction.INCOMING));
        // Same race with the other arrival order.
        assertTrue(ConnectionManager.keepCandidate(A, B, Direction.INCOMING, Direction.OUTGOING));
        assertFalse(ConnectionManager.keepCandidate(B, A, Direction.INCOMING, Direction.OUTGOING));
    }

    @Test
    void newerLinkFromTheSameSideReplacesTheOldOne() {
        assertTrue(ConnectionManager.keepCandidate(A, B, Direction.INCOMING, Direction.INCOMING));
        assertTrue(ConnectionManager.keepCandidate(A, B, Direction.OUTGOING, Direction.OUTGOING));
    }
}
