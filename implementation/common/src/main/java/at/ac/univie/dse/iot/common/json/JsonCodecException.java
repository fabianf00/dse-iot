package at.ac.univie.dse.iot.common.json;

/** Thrown when a value cannot be converted to or from JSON. */
public class JsonCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonCodecException(String message) {
        super(message);
    }

    public JsonCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
