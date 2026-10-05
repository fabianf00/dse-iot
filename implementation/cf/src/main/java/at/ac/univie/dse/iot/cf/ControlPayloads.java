package at.ac.univie.dse.iot.cf;

/** Payloads of the hop-local control messages. */
final class ControlPayloads {

    /** Value of {@link Hello#magic()}; identifies a CF peer on a scanned port. */
    static final String MAGIC = "DSE-IOT-CF";

    private ControlPayloads() {
    }

    /**
     * Payload of {@link MessageType#HELLO}.
     *
     * @param advertisedHost host under which the sender accepts connections, together with
     *                       {@code listenPort}; lets the receiver avoid scanning a peer it is
     *                       already connected to
     */
    record Hello(NodeId nodeId, String advertisedHost, int listenPort, String magic) {
    }

    /** Payload of {@link MessageType#HELLO_ACK}. {@code reason} is {@code null} when accepted. */
    record HelloAck(NodeId nodeId, boolean accepted, RejectReason reason) {
    }

    /** Payload of {@link MessageType#BYE}. */
    record Bye(ByeReason reason) {
    }

    /** Why a handshake was rejected. */
    enum RejectReason {
        /** The scanner reached its own node, e.g. under another address. */
        SELF_CONNECTION,
        /** A link to this node already exists, in either direction. */
        ALREADY_CONNECTED,
        /** The peer speaks another protocol version. */
        UNSUPPORTED_VERSION,
        /** The node is shutting down. */
        SHUTTING_DOWN
    }

    /** Why a link is closed. */
    enum ByeReason {
        /** Rolling refresh: the oldest outgoing link is replaced by a new random one. */
        REFRESH,
        SHUTDOWN,
        /** Too many malformed or unexpected messages. */
        PROTOCOL_ERROR,
        /** Two links between the same pair of nodes were opened at the same time; this one loses. */
        DUPLICATE_CONNECTION
    }
}
