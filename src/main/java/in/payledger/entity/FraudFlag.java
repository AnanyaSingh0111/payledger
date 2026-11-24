package in.payledger.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A completed transaction that a fraud rule wants a human to review. */
@Entity
@Table(name = "fraud_flags")
public class FraudFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "rule_name", nullable = false, length = 50)
    private String ruleName;

    @Column(nullable = false, length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudFlagStatus status = FraudFlagStatus.OPEN;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    @Column(length = 255)
    private String reviewNote;

    protected FraudFlag() { }

    public FraudFlag(Long transactionId, Long accountId, String ruleName, String reason) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.ruleName = ruleName;
        this.reason = reason;
        this.createdAt = LocalDateTime.now();
    }

    public void review(FraudFlagStatus outcome, Long adminId, String note) {
        this.status = outcome;
        this.reviewedBy = adminId;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNote = note;
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getAccountId() { return accountId; }
    public String getRuleName() { return ruleName; }
    public String getReason() { return reason; }
    public FraudFlagStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
}
