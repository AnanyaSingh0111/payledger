package in.payledger.dto;

import in.payledger.entity.Transaction;
import in.payledger.entity.TransactionStatus;
import in.payledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        TransactionType type,
        TransactionStatus status,
        Long sourceAccountId,
        Long destinationAccountId,
        BigDecimal amount,
        String currency,
        String category,
        String description,
        Long refundOfTransactionId,
        LocalDateTime createdAt) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(), t.getType(), t.getStatus(),
                t.getSourceAccountId(), t.getDestinationAccountId(),
                t.getAmount(), t.getCurrency(), t.getCategory(), t.getDescription(),
                t.getRefundOfTransactionId(), t.getCreatedAt());
    }
}
