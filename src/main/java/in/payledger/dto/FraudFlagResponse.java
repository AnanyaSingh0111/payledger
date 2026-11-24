package in.payledger.dto;

import in.payledger.entity.FraudFlag;
import in.payledger.entity.FraudFlagStatus;

import java.time.LocalDateTime;

public record FraudFlagResponse(
        Long id,
        Long transactionId,
        Long accountId,
        String ruleName,
        String reason,
        FraudFlagStatus status,
        LocalDateTime createdAt,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String reviewNote) {

    public static FraudFlagResponse from(FraudFlag f) {
        return new FraudFlagResponse(f.getId(), f.getTransactionId(), f.getAccountId(), f.getRuleName(),
                f.getReason(), f.getStatus(), f.getCreatedAt(), f.getReviewedBy(), f.getReviewedAt(),
                f.getReviewNote());
    }
}
