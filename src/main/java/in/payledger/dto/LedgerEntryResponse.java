package in.payledger.dto;

import in.payledger.entity.EntryDirection;
import in.payledger.entity.LedgerEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LedgerEntryResponse(
        Long id,
        Long transactionId,
        EntryDirection direction,
        BigDecimal amount,
        LocalDateTime createdAt) {

    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(e.getId(), e.getTransaction().getId(),
                e.getDirection(), e.getAmount(), e.getCreatedAt());
    }
}
