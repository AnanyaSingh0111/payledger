package in.payledger.dto;

import in.payledger.entity.AccountType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(

        @NotBlank(message = "Account name is required")
        @Size(max = 100, message = "Account name must not exceed 100 characters")
        String name,

        @NotNull(message = "Account type is required")
        AccountType type,

        /** ISO 4217 code such as INR or USD. Defaults to INR. */
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code such as INR")
        String currency,

        /** Optional. Recorded in the ledger as a TOP_UP, never written straight into the balance. */
        @DecimalMin(value = "0.00", message = "Initial balance cannot be negative")
        @DecimalMax(value = "10000000.00", message = "Initial balance must not exceed 10,000,000")
        @Digits(integer = 17, fraction = 2, message = "Initial balance can have at most 2 decimal places")
        BigDecimal initialBalance) {

    public CreateAccountRequest(String name, AccountType type) {
        this(name, type, null, null);
    }
}
