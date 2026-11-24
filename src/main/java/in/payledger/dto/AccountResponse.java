package in.payledger.dto;

import in.payledger.entity.Account;
import in.payledger.entity.AccountStatus;
import in.payledger.entity.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountResponse(
        Long id,
        String name,
        AccountType type,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        LocalDateTime createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getCurrency(),
                a.getBalance(), a.getStatus(), a.getCreatedAt());
    }
}
