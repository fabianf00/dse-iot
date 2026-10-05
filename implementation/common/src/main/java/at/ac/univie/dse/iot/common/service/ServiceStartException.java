package at.ac.univie.dse.iot.common.service;

/** Thrown when a microservice cannot be started. */
public class ServiceStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
