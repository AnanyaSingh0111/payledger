package in.payledger.exception;

/** The request is well-formed but breaks a business rule (HTTP 400). */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message) {
        super(message);
    }
}
