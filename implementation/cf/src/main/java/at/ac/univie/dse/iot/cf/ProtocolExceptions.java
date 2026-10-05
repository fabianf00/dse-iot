package at.ac.univie.dse.iot.cf;

import java.io.IOException;

/** Exceptions raised while reading or writing the CF wire protocol. */
final class ProtocolExceptions {

    private ProtocolExceptions() {
    }

    /**
     * The byte stream can no longer be trusted, e.g. an invalid frame length. The connection must
     * be closed.
     */
    static final class ProtocolViolationException extends IOException {
        private static final long serialVersionUID = 1L;

        ProtocolViolationException(String message) {
            super(message);
        }
    }

    /**
     * One frame was complete but did not contain a valid message. The stream is still in sync, so
     * the connection may continue.
     */
    static final class MalformedMessageException extends IOException {
        private static final long serialVersionUID = 1L;

        MalformedMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A message is too large to be sent in one frame. */
    static final class FrameTooLargeException extends CfException {
        private static final long serialVersionUID = 1L;

        FrameTooLargeException(String message) {
            super(message);
        }
    }

    /** The other side rejected the handshake, or the handshake answer was a rejection. */
    static final class HandshakeRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        private final ControlPayloads.RejectReason reason;

        HandshakeRejectedException(ControlPayloads.RejectReason reason) {
            super("handshake rejected: " + reason);
            this.reason = reason;
        }

        ControlPayloads.RejectReason reason() {
            return reason;
        }
    }
}
