package in.payledger.dto;

import in.payledger.entity.TransactionStatus;
import in.payledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Optional filters for transaction history. Every null field is ignored. */
public record TransactionFilter(
        Long accountId,
        TransactionType type,
        TransactionStatus status,
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal minAmount,
        BigDecimal maxAmount) {

    public static TransactionFilter none() {
        return new TransactionFilter(null, null, null, null, null, null, null);
    }

    public static TransactionFilter forAccount(Long accountId) {
        return new TransactionFilter(accountId, null, null, null, null, null, null);
    }
}
