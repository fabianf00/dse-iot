package at.ac.univie.dse.iot.msml.storage;

/** The database cannot be opened or used. */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
