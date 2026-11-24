package in.payledger.fraud;

import in.payledger.entity.TransactionType;
import in.payledger.repository.TransactionRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * BLOCKS a withdrawal or transfer that would push the account's outgoing total for today
 * over the limit.
 *
 * Concurrency: two parallel transfers of 60k against a 100k limit must not both pass. That
 * holds because (1) the caller already holds the source account's row lock, so debits of one
 * account run one at a time, and (2) the money operation runs at READ COMMITTED, so this SUM
 * sees every transfer committed while we waited for the lock. Under MySQL's default REPEATABLE
 * READ the SUM would read the snapshot taken at the transaction's first read and could miss them.
 */
@Component
@Order(1)
public class DailyLimitRule implements FraudRule {

    static final Set<TransactionType> OUTGOING = EnumSet.of(TransactionType.WITHDRAWAL, TransactionType.TRANSFER);

    private final TransactionRepository transactionRepository;
    private final FraudProperties properties;

    public DailyLimitRule(TransactionRepository transactionRepository, FraudProperties properties) {
        this.transactionRepository = transactionRepository;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "DAILY_LIMIT";
    }

    @Override
    public Optional<FraudVerdict> evaluate(FraudContext ctx) {
        if (!ctx.isOutgoing()) {
            return Optional.empty();
        }
        BigDecimal sentToday = transactionRepository.sumOutgoingSince(
                ctx.source().getId(), OUTGOING, ctx.now().toLocalDate().atStartOfDay());

        if (sentToday.add(ctx.amount()).compareTo(properties.dailyLimit()) > 0) {
            return Optional.of(FraudVerdict.block(name(),
                    "Daily outgoing limit of " + properties.dailyLimit().toPlainString()
                            + " exceeded: " + sentToday.toPlainString() + " already sent today"));
        }
        return Optional.empty();
    }
}
