package at.ac.univie.dse.iot.common.fogapi;

import at.ac.univie.dse.iot.common.fogapi.FogMessage.ErrorCode;
import java.util.UUID;

/** A datagram that is not a valid Fog API message; carries what to answer. */
public class FogProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final UUID requestId;

    public FogProtocolException(ErrorCode code, UUID requestId, String message) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }

    public ErrorCode code() {
        return code;
    }

    /** Request id of the offending message, or {@code null} if it could not be read. */
    public UUID requestId() {
        return requestId;
    }

    /** The ERROR message to send back. */
    public FogMessage.Error toErrorMessage() {
        return FogMessage.Error.of(requestId, code, getMessage());
    }
}
