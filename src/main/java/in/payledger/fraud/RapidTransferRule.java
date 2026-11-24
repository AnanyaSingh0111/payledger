package in.payledger.fraud;

import in.payledger.repository.TransactionRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** FLAGS an account that sends money out many times in a short window (a typical takeover pattern). */
@Component
@Order(2)
public class RapidTransferRule implements FraudRule {

    private final TransactionRepository transactionRepository;
    private final FraudProperties properties;

    public RapidTransferRule(TransactionRepository transactionRepository, FraudProperties properties) {
        this.transactionRepository = transactionRepository;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "RAPID_TRANSFERS";
    }

    @Override
    public Optional<FraudVerdict> evaluate(FraudContext ctx) {
        if (!ctx.isOutgoing()) {
            return Optional.empty();
        }
        long recent = transactionRepository.countOutgoingSince(ctx.source().getId(), DailyLimitRule.OUTGOING,
                ctx.now().minusSeconds(properties.rapidWindowSeconds()));

        long includingThisOne = recent + 1;
        if (includingThisOne > properties.rapidMaxTransfers()) {
            return Optional.of(FraudVerdict.flag(name(),
                    includingThisOne + " outgoing transactions within " + properties.rapidWindowSeconds()
                            + "s (threshold " + properties.rapidMaxTransfers() + ")"));
        }
        return Optional.empty();
    }
}
