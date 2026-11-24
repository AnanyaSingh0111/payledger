package in.payledger.exception;

/** The account is frozen and can neither send nor receive money (HTTP 409). */
public class AccountFrozenException extends RuntimeException {

    public AccountFrozenException(String message) {
        super(message);
    }
}
