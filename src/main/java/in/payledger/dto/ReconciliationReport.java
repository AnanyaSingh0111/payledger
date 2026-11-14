package in.payledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ReconciliationReport(
        LocalDateTime runAt,
        int accountsChecked,
        BigDecimal ledgerNet,
        boolean ledgerBalanced,
        boolean healthy,
        List<MismatchDto> mismatches) { }