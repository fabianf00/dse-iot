package at.ac.univie.dse.iot.cf;

import java.util.function.LongSupplier;

/**
 * Detects dead links, including half-open TCP connections where the peer vanished without
 * closing the socket.
 *
 * <p>A link on which nothing was sent for {@code intervalMillis} gets a PING, which the peer
 * answers with PONG. A link on which nothing was received for {@code timeoutMillis} is closed.
 * A link whose BYE could not be written within {@code timeoutMillis} is closed as well.
 */
final class HeartbeatMonitor {

    private final NodeId self;
    private final ConnectionManager connections;
    private final long intervalMillis;
    private final long timeoutMillis;
    private final LongSupplier clock;

    HeartbeatMonitor(NodeId self, ConnectionManager connections, long intervalMillis, long timeoutMillis, LongSupplier clock) {
        this.self = self;
        this.connections = connections;
        this.intervalMillis = intervalMillis;
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
    }

    void check() {
        long now = clock.getAsLong();
        for (PeerConnection connection : connections.connections()) {
            if (connection.isClosingLongerThan(timeoutMillis, now)) {
                connection.close("goodbye could not be delivered in time");
            } else if (now - connection.lastReceivedAt() > timeoutMillis) {
                connection.close("heartbeat timeout, nothing received for " + (now - connection.lastReceivedAt()) + " ms");
            } else if (now - connection.lastSentAt() >= intervalMillis) {
                connection.send(CfMessage.control(MessageType.PING, self, null, now));
            }
        }
    }
}
