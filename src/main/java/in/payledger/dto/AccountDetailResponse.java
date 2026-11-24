package in.payledger.dto;

import java.math.BigDecimal;

/** An account plus its balance recomputed from the ledger, so the client can see they agree. */
public record AccountDetailResponse(
        AccountResponse account,
        BigDecimal ledgerBalance,
        boolean balanceMatchesLedger) { }
