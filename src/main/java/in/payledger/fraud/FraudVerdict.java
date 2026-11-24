package in.payledger.fraud;

public record FraudVerdict(String ruleName, Action action, String reason) {

    public enum Action {
        /** Let the transaction through, but record a flag for an admin to review. */
        FLAG,
        /** Reject the transaction; no money moves. */
        BLOCK
    }

    public static FraudVerdict flag(String rule, String reason) {
        return new FraudVerdict(rule, Action.FLAG, reason);
    }

    public static FraudVerdict block(String rule, String reason) {
        return new FraudVerdict(rule, Action.BLOCK, reason);
    }
}
