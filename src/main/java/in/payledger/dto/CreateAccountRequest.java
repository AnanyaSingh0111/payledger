package in.payledger.dto;

import in.payledger.entity.AccountType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Opens the caller's wallet for one currency. A user has at most one wallet per currency,
 * so {"currency":"INR"} is a complete request; everything else is optional.
 */
public record CreateAccountRequest(

        /** ISO 4217 code such as INR or USD. Defaults to INR. */
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code such as INR")
        String currency,

        /** Optional label. Defaults to "<currency> Wallet". */
        @Size(max = 100, message = "Account name must not exceed 100 characters")
        String name,

        /** Optional. Defaults to WALLET. */
        AccountType type,

        /** Optional. Recorded in the ledger as a TOP_UP, never written straight into the balance. */
        @DecimalMin(value = "0.00", message = "Initial balance cannot be negative")
        @DecimalMax(value = "10000000.00", message = "Initial balance must not exceed 10,000,000")
        @Digits(integer = 17, fraction = 2, message = "Initial balance can have at most 2 decimal places")
        BigDecimal initialBalance) {

    public static CreateAccountRequest of(String currency) {
        return new CreateAccountRequest(currency, null, null, null);
    }
}
