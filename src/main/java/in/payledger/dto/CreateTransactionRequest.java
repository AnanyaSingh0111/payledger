package in.payledger.dto;

import in.payledger.entity.TransactionType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * TOP_UP: destinationAccountId only. WITHDRAWAL: sourceAccountId only.
 * TRANSFER: both (the destination may belong to another user). Refunds use their own endpoint.
 */
public record CreateTransactionRequest(

        @NotNull(message = "Transaction type is required")
        TransactionType type,

        Long sourceAccountId,

        Long destinationAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @DecimalMax(value = "10000000.00", message = "Amount must not exceed 10,000,000")
        // Without this, 10.005 would pass validation and be rounded silently by DECIMAL(19,2)
        @Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 100, message = "Category must not exceed 100 characters")
        String category,

        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description) {

    public static CreateTransactionRequest topUp(Long destinationAccountId, BigDecimal amount) {
        return new CreateTransactionRequest(TransactionType.TOP_UP, null, destinationAccountId, amount, null, null);
    }

    public static CreateTransactionRequest withdrawal(Long sourceAccountId, BigDecimal amount) {
        return new CreateTransactionRequest(TransactionType.WITHDRAWAL, sourceAccountId, null, amount, null, null);
    }

    public static CreateTransactionRequest transfer(Long sourceAccountId, Long destinationAccountId, BigDecimal amount) {
        return new CreateTransactionRequest(TransactionType.TRANSFER, sourceAccountId, destinationAccountId, amount, null, null);
    }
}
