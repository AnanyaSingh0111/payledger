package in.payledger.entity;

public enum TransactionType {
    /** Money enters a wallet from outside the system. */
    TOP_UP,
    /** Money leaves a wallet to outside the system. */
    WITHDRAWAL,
    /** Money moves between two wallets, possibly owned by different users. */
    TRANSFER,
    /** Reverses a TRANSFER: the receiver sends the amount back. */
    REFUND
}
