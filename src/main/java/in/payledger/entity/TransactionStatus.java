package in.payledger.entity;

public enum TransactionStatus {
    COMPLETED,
    /** A TRANSFER that has been fully reversed by a REFUND transaction. */
    REFUNDED
}
