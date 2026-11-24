package in.payledger.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A wallet. Each user has at most one wallet per currency (UNIQUE(user_id, currency)). */
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_accounts_user_currency", columnNames = {"user_id", "currency"}))
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic-locking version. Money movement uses pessimistic row locks, so this is a
     * safety net that also catches any update path that forgets to lock.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "INR";

    /** Denormalised copy of the ledger total, kept for fast reads and checked by reconciliation. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Account() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isFrozen() { return status == AccountStatus.FROZEN; }

    public boolean isOwnedBy(Long userId) { return user.getId().equals(userId); }

    public void credit(BigDecimal amount) { balance = balance.add(amount); }

    public void debit(BigDecimal amount) { balance = balance.subtract(amount); }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
