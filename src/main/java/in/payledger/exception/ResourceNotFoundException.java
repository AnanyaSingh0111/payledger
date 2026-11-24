package in.payledger.exception;

/** The resource does not exist or belongs to someone else (HTTP 404). */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
