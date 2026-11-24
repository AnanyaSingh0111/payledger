package in.payledger.fraud;

import java.util.Optional;

/**
 * Strategy interface: each rule is an independent Spring bean, and FraudService runs all of
 * them. Adding a rule means adding one class; nothing else changes (open/closed principle).
 */
public interface FraudRule {

    String name();

    /** @return a verdict if the rule fires, or empty if the transaction looks fine to it */
    Optional<FraudVerdict> evaluate(FraudContext context);
}
