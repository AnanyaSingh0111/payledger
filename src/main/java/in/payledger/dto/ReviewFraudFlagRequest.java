package in.payledger.dto;

import in.payledger.entity.FraudFlagStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewFraudFlagRequest(

        /** CLEARED (false positive) or CONFIRMED (real fraud). */
        @NotNull(message = "Outcome is required")
        FraudFlagStatus outcome,

        @Size(max = 255, message = "Note must not exceed 255 characters")
        String note) { }
