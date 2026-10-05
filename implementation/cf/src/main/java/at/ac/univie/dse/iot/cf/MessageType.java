package at.ac.univie.dse.iot.cf;

/**
 * Type of a CF message.
 *
 * <p>Control messages are hop-local: they are exchanged between two directly connected peers
 * and never forwarded. Only {@link #DATA} messages are flooded through the overlay.
 */
public enum MessageType {
    /** First frame of the connecting peer, identifies it as a CF peer. */
    HELLO,
    /** Answer of the accepting peer to {@link #HELLO}: accepted or rejected with a reason. */
    HELLO_ACK,
    /** Liveness check on an idle link. */
    PING,
    /** Answer to {@link #PING}. */
    PONG,
    /** Graceful close of a link, with a reason. */
    BYE,
    /** Application data on a topic, flooded with TTL. */
    DATA;

    public boolean isControl() {
        return this != DATA;
    }
}
