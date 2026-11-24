package in.payledger.exception;

/** A fraud rule blocked the transaction (HTTP 422). */
public class TransactionRejectedException extends RuntimeException {

    public TransactionRejectedException(String message) {
        super(message);
    }
}
