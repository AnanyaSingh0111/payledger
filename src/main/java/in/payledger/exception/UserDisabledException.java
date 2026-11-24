package in.payledger.exception;

/** The user has been disabled by an admin (HTTP 403). */
public class UserDisabledException extends RuntimeException {

    public UserDisabledException(String message) {
        super(message);
    }
}
