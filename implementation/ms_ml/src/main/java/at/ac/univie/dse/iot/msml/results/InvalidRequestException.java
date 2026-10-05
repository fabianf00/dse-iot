package at.ac.univie.dse.iot.msml.results;

/** A request to manage stored results is invalid; the message tells the caller what to fix. */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
