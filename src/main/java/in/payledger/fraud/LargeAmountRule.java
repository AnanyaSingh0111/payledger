package in.payledger.fraud;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** FLAGS any single transaction (including top-ups) at or above a threshold. */
@Component
@Order(3)
public class LargeAmountRule implements FraudRule {

    private final FraudProperties properties;

    public LargeAmountRule(FraudProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "LARGE_AMOUNT";
    }

    @Override
    public Optional<FraudVerdict> evaluate(FraudContext ctx) {
        if (ctx.amount().compareTo(properties.largeAmount()) >= 0) {
            return Optional.of(FraudVerdict.flag(name(),
                    "Amount " + ctx.amount().toPlainString() + " is at or above "
                            + properties.largeAmount().toPlainString()));
        }
        return Optional.empty();
    }
}
