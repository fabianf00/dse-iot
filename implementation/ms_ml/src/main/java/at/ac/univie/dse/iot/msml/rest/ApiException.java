package at.ac.univie.dse.iot.msml.rest;

/** Ends a request with an HTTP error status and a problem-details body. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, detail);
    }

    public int status() {
        return status;
    }
}
