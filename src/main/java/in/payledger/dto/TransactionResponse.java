package in.payledger.dto;

import in.payledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionResponse {

    private Long id;
    private Long sourceAccountId;
    private Long destinationAccountId;
    private TransactionType type;
    private BigDecimal amount;
    private String category;
    private String description;
    private LocalDateTime createdAt;

    public TransactionResponse() {
    }

    public TransactionResponse(
            Long id,
            Long sourceAccountId,
            Long destinationAccountId,
            TransactionType type,
            BigDecimal amount,
            String category,
            String description,
            LocalDateTime createdAt) {

        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.type = type;
        this.amount = amount;
        this.category = category;
        this.description = description;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public Long getDestinationAccountId() {
        return destinationAccountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}