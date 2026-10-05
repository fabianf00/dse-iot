package at.ac.univie.dse.iot.cf;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decentralized peer discovery: while outgoing slots are free, probes random candidates and
 * connects to those that answer the handshake.
 *
 * <p>Candidates without a usable peer (nothing listening, not a CF peer, rejected) are skipped
 * for a backoff period, so the scanner moves on to other addresses.
 */
final class PeerScanner {

    /** Opens a link to a candidate; returns {@code true} if a new link was established. */
    @FunctionalInterface
    interface Connector {
        boolean connect(InetSocketAddress candidate);
    }

    private final ScanStrategy strategy;
    private final ConnectionManager connections;
    private final Connector connector;
    private final InetSocketAddress ownAddress;
    private final long backoffMillis;
    private final int maxAttemptsPerRound;
    private final LongSupplier clock;
    private final Map<InetSocketAddress, Long> backoffUntil = new ConcurrentHashMap<>();

    PeerScanner(ScanStrategy strategy, ConnectionManager connections, Connector connector,
            InetSocketAddress ownAddress, long backoffMillis, int maxAttemptsPerRound, LongSupplier clock) {
        this.strategy = strategy;
        this.connections = connections;
        this.connector = connector;
        this.ownAddress = ownAddress;
        this.backoffMillis = backoffMillis;
        this.maxAttemptsPerRound = maxAttemptsPerRound;
        this.clock = clock;
    }

    /** One scan round, run periodically by the node's scheduler. */
    void scanRound() {
        long now = clock.getAsLong();
        backoffUntil.values().removeIf(until -> until <= now);
        int attempts = 0;
        while (connections.hasFreeOutgoingSlot() && attempts < maxAttemptsPerRound
                && !Thread.currentThread().isInterrupted()) {
            Optional<InetSocketAddress> candidate = strategy.nextCandidate(excludedAddresses());
            if (candidate.isEmpty()) {
                return;
            }
            attempts++;
            if (!connector.connect(candidate.get())) {
                backoff(candidate.get());
            }
        }
    }

    /** Skips {@code address} for the backoff period. */
    void backoff(InetSocketAddress address) {
        if (address != null) {
            backoffUntil.put(address, clock.getAsLong() + backoffMillis);
        }
    }

    private Set<InetSocketAddress> excludedAddresses() {
        Set<InetSocketAddress> excluded = new HashSet<>(connections.listenAddresses());
        excluded.add(ownAddress);
        excluded.addAll(backoffUntil.keySet());
        return excluded;
    }
}
