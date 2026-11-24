package in.payledger.exception;

/** An Idempotency-Key was reused for a different request (HTTP 422). */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
