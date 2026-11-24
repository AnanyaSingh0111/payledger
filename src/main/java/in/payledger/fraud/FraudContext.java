package in.payledger.fraud;

import in.payledger.entity.Account;
import in.payledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Everything a rule may look at. Built AFTER the accounts are locked, so rules that query
 * recent activity of the source account see a stable, serialized view of it.
 */
public record FraudContext(
        Long userId,
        TransactionType type,
        Account source,
        Account destination,
        BigDecimal amount,
        LocalDateTime now) {

    public boolean isOutgoing() {
        return source != null && (type == TransactionType.WITHDRAWAL || type == TransactionType.TRANSFER);
    }
}
