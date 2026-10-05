package at.ac.univie.dse.iot.cf;

/** Thrown when the CF cannot do what an MS asked for, e.g. bind its port or send a too large payload. */
public class CfException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CfException(String message) {
        super(message);
    }

    public CfException(String message, Throwable cause) {
        super(message, cause);
    }
}
