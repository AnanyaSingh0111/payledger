package in.payledger.exception;

/** The source account does not hold enough money (HTTP 422). */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
