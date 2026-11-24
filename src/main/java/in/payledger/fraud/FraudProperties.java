package in.payledger.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * @param dailyLimit         max money one account may send out per calendar day (BLOCK)
 * @param largeAmount        any single transaction at or above this is flagged (FLAG)
 * @param rapidWindowSeconds sliding window for the rapid-transfer rule
 * @param rapidMaxTransfers  more outgoing transactions than this inside the window are flagged (FLAG)
 */
@ConfigurationProperties("app.fraud")
public record FraudProperties(
        @DefaultValue("200000.00") BigDecimal dailyLimit,
        @DefaultValue("50000.00") BigDecimal largeAmount,
        @DefaultValue("60") int rapidWindowSeconds,
        @DefaultValue("10") int rapidMaxTransfers) { }
