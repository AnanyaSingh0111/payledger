package in.payledger.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One side of a double-entry posting. Append-only on three levels: Hibernate never issues an
 * UPDATE (@Immutable), the repository exposes no update or delete method, and database triggers
 * reject both.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** null = the "external" counterparty (money entering or leaving the system). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected LedgerEntry() { }

    public LedgerEntry(Transaction transaction, Account account,
                       EntryDirection direction, BigDecimal amount) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.createdAt = LocalDateTime.now();
    }

    /** Signed effect on the account: credits add, debits subtract. */
    public BigDecimal signedAmount() {
        return direction == EntryDirection.CREDIT ? amount : amount.negate();
    }

    public Long getId() { return id; }
    public Transaction getTransaction() { return transaction; }
    public Account getAccount() { return account; }
    public EntryDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
