package in.payledger.dto;

import java.math.BigDecimal;

public record MismatchDto(
        Long accountId,
        BigDecimal storedBalance,
        BigDecimal ledgerBalance,
        BigDecimal difference) { }