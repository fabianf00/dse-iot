package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.cf.ControlPayloads.ByeReason;
import at.ac.univie.dse.iot.cf.ControlPayloads.RejectReason;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * All established links of a node, at most one per remote node. Enforces the limit of outgoing
 * links; incoming links are unlimited.
 *
 * <p>If two nodes connect to each other at the same moment, both handshakes succeed and both
 * nodes end up with two links to each other. Both nodes then keep the link opened by the node
 * with the smaller id and close the other one, so they agree without extra messages.
 */
final class ConnectionManager implements MessageRouter.Broadcaster {

    enum Registration {
        ACCEPTED,
        /** A link to the same node exists and wins over the new one. */
        REJECTED_DUPLICATE,
        /** The new link is outgoing and all outgoing slots are taken. */
        REJECTED_OUTGOING_LIMIT,
        /** The node is shutting down. */
        REJECTED_CLOSED
    }

    private final NodeId self;
    private final int maxOutgoing;
    private final ConcurrentMap<NodeId, PeerConnection> connections = new ConcurrentHashMap<>();
    private boolean closed;

    ConnectionManager(NodeId self, int maxOutgoing) {
        this.self = self;
        this.maxOutgoing = maxOutgoing;
    }

    /**
     * Adds a link after its handshake. On {@link Registration#ACCEPTED} the caller starts it;
     * otherwise the caller closes it. A replaced duplicate is closed here.
     */
    synchronized Registration register(PeerConnection candidate) {
        if (closed) {
            return Registration.REJECTED_CLOSED;
        }
        PeerConnection existing = connections.get(candidate.remoteId());
        if (existing == null) {
            if (candidate.direction() == Direction.OUTGOING && outgoingCount() >= maxOutgoing) {
                return Registration.REJECTED_OUTGOING_LIMIT;
            }
            connections.put(candidate.remoteId(), candidate);
            return Registration.ACCEPTED;
        }
        if (!keepCandidate(self, candidate.remoteId(), existing.direction(), candidate.direction())) {
            return Registration.REJECTED_DUPLICATE;
        }
        connections.put(candidate.remoteId(), candidate);
        existing.closeGracefully(ByeReason.DUPLICATE_CONNECTION);
        return Registration.ACCEPTED;
    }

    /**
     * Decides which of two links to the same remote node survives. Both nodes evaluate this with
     * swapped roles and reach the same result: the link opened by the smaller node id wins. If
     * both links were opened by the same side (e.g. the remote reconnected after a restart before
     * the old link timed out), the newer one wins.
     */
    static boolean keepCandidate(NodeId self, NodeId remote, Direction existing, Direction candidate) {
        if (existing == candidate) {
            return true;
        }
        NodeId candidateOpener = candidate == Direction.OUTGOING ? self : remote;
        NodeId smaller = self.compareTo(remote) <= 0 ? self : remote;
        return candidateOpener.equals(smaller);
    }

    /** Removes {@code connection} if it is still the registered link to its node. */
    void remove(PeerConnection connection) {
        connections.remove(connection.remoteId(), connection);
    }

    /** Checks whether a handshake from {@code remote} may be accepted. */
    synchronized Optional<RejectReason> admission(NodeId remote) {
        if (closed) {
            return Optional.of(RejectReason.SHUTTING_DOWN);
        }
        if (connections.containsKey(remote)) {
            return Optional.of(RejectReason.ALREADY_CONNECTED);
        }
        return Optional.empty();
    }

    @Override
    public void broadcast(CfMessage message, NodeId except) {
        for (PeerConnection connection : connections.values()) {
            if (!connection.remoteId().equals(except)) {
                connection.send(message);
            }
        }
    }

    /** Marks the manager closed and returns all links, which the caller then closes. */
    synchronized List<PeerConnection> closeAll() {
        closed = true;
        List<PeerConnection> all = new ArrayList<>(connections.values());
        connections.clear();
        return all;
    }

    int outgoingCount() {
        return (int) connections.values().stream().filter(c -> c.direction() == Direction.OUTGOING).count();
    }

    boolean hasFreeOutgoingSlot() {
        return outgoingCount() < maxOutgoing;
    }

    int maxOutgoing() {
        return maxOutgoing;
    }

    Optional<PeerConnection> oldestOutgoing() {
        return connections.values().stream()
                .filter(c -> c.direction() == Direction.OUTGOING)
                .min(Comparator.comparing(PeerConnection::connectedSince));
    }

    Collection<PeerConnection> connections() {
        return List.copyOf(connections.values());
    }

    /** Listen addresses of all connected peers; the scanner skips them. */
    Set<InetSocketAddress> listenAddresses() {
        return connections.values().stream()
                .map(PeerConnection::remoteListenAddress)
                .collect(Collectors.toSet());
    }

    Set<PeerInfo> peerInfos() {
        return connections.values().stream().map(PeerConnection::info).collect(Collectors.toUnmodifiableSet());
    }
}
