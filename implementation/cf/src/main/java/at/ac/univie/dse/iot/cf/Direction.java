package at.ac.univie.dse.iot.cf;

/** Which side opened a link. Each node opens at most {@code maxOutgoingPeers} links itself. */
public enum Direction {
    /** Opened by the remote peer. Unlimited. */
    INCOMING,
    /** Opened by this node's scanner. */
    OUTGOING
}
