package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.cf.ControlPayloads.ByeReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rolling refresh of outgoing links: when all outgoing slots are taken, the oldest outgoing link
 * is closed and its peer is skipped by the scanner for the backoff period. The scanner then
 * fills the slot with a random new peer.
 *
 * <p>This keeps reshuffling the overlay, so groups of nodes that only link to each other
 * ("islands") eventually connect to the rest. Only one link is replaced at a time, so a node
 * never loses all its links at once.
 */
final class PeerRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(PeerRefresher.class);

    private final ConnectionManager connections;
    private final PeerScanner scanner;

    PeerRefresher(ConnectionManager connections, PeerScanner scanner) {
        this.connections = connections;
        this.scanner = scanner;
    }

    void refresh() {
        if (connections.hasFreeOutgoingSlot()) {
            // The scanner is still looking for peers; dropping a link now would only cost connectivity.
            return;
        }
        connections.oldestOutgoing().ifPresent(oldest -> {
            LOG.debug("Refreshing link to {}", oldest.remoteId());
            scanner.backoff(oldest.remoteListenAddress());
            oldest.closeGracefully(ByeReason.REFRESH);
        });
    }
}
