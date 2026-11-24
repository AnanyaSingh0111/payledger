package in.payledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param ledgerNet                 sum of every ledger entry; double entry means it must be 0
 * @param mismatches                accounts whose stored balance differs from their ledger total
 * @param unbalancedTransactionIds  transactions whose own entries do not net to zero
 */
public record ReconciliationReport(
        LocalDateTime runAt,
        int accountsChecked,
        long entriesChecked,
        BigDecimal ledgerNet,
        boolean ledgerBalanced,
        boolean healthy,
        List<MismatchDto> mismatches,
        List<Long> unbalancedTransactionIds) { }
