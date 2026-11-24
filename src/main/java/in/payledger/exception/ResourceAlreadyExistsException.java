package in.payledger.exception;

/** A unique resource already exists (HTTP 409). */
public class ResourceAlreadyExistsException extends RuntimeException {

    public ResourceAlreadyExistsException(String message) {
        super(message);
    }
}
