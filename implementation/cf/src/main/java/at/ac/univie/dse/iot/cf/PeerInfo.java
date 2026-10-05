package at.ac.univie.dse.iot.cf;

import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * Snapshot of one link to a peer.
 *
 * @param nodeId         id of the peer
 * @param listenAddress  address the peer accepts connections on
 * @param direction      who opened the link
 * @param connectedSince when the handshake completed
 */
public record PeerInfo(NodeId nodeId, InetSocketAddress listenAddress, Direction direction, Instant connectedSince) {
}
