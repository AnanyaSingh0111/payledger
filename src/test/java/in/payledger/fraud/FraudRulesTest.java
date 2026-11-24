package in.payledger.fraud;

import in.payledger.audit.AuditService;
import in.payledger.entity.Account;
import in.payledger.entity.TransactionType;
import in.payledger.exception.TransactionRejectedException;
import in.payledger.outbox.OutboxService;
import in.payledger.repository.FraudFlagRepository;
import in.payledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure unit tests: no Spring, no database. The repository is mocked with Mockito. */
@ExtendWith(MockitoExtension.class)
class FraudRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 14, 30);

    @Mock TransactionRepository transactionRepository;

    private final FraudProperties props = new FraudProperties(
            new BigDecimal("1000.00"), new BigDecimal("500.00"), 60, 3);

    private Account source;

    @BeforeEach
    void setUp() {
        source = mock(Account.class);
        // lenient: not every test reaches the repository
        org.mockito.Mockito.lenient().when(source.getId()).thenReturn(7L);
    }

    private FraudContext ctx(TransactionType type, String amount) {
        return new FraudContext(1L, type, type == TransactionType.TOP_UP ? null : source, null,
                new BigDecimal(amount), NOW);
    }

    // ---------- DailyLimitRule ----------

    @Test
    void dailyLimitBlocksWhenTodaysTotalWouldExceedTheLimit() {
        when(transactionRepository.sumOutgoingSince(eq(7L), any(), eq(NOW.toLocalDate().atStartOfDay())))
                .thenReturn(new BigDecimal("900.00"));

        var verdict = new DailyLimitRule(transactionRepository, props).evaluate(ctx(TransactionType.TRANSFER, "100.01"));

        assertThat(verdict).hasValueSatisfying(v -> {
            assertThat(v.action()).isEqualTo(FraudVerdict.Action.BLOCK);
            assertThat(v.ruleName()).isEqualTo("DAILY_LIMIT");
        });
    }

    @Test
    void dailyLimitAllowsExactlyReachingTheLimit() {
        when(transactionRepository.sumOutgoingSince(eq(7L), any(), any())).thenReturn(new BigDecimal("900.00"));

        assertThat(new DailyLimitRule(transactionRepository, props).evaluate(ctx(TransactionType.WITHDRAWAL, "100.00")))
                .isEmpty();
    }

    @Test
    void dailyLimitIgnoresTopUps() {
        assertThat(new DailyLimitRule(transactionRepository, props).evaluate(ctx(TransactionType.TOP_UP, "99999.00")))
                .isEmpty();
        verifyNoInteractions(transactionRepository);
    }

    // ---------- RapidTransferRule ----------

    @Test
    void rapidTransfersAreFlaggedNotBlocked() {
        when(transactionRepository.countOutgoingSince(eq(7L), any(), eq(NOW.minusSeconds(60)))).thenReturn(3L);

        var verdict = new RapidTransferRule(transactionRepository, props).evaluate(ctx(TransactionType.TRANSFER, "1.00"));

        assertThat(verdict).hasValueSatisfying(v -> assertThat(v.action()).isEqualTo(FraudVerdict.Action.FLAG));
    }

    @Test
    void aFewTransfersInTheWindowAreFine() {
        when(transactionRepository.countOutgoingSince(anyLong(), any(), any())).thenReturn(2L);

        assertThat(new RapidTransferRule(transactionRepository, props).evaluate(ctx(TransactionType.TRANSFER, "1.00")))
                .isEmpty();
    }

    // ---------- LargeAmountRule ----------

    @Test
    void largeAmountFlagsAtTheThresholdForAnyType() {
        var rule = new LargeAmountRule(props);
        assertThat(rule.evaluate(ctx(TransactionType.TOP_UP, "500.00"))).isPresent();
        assertThat(rule.evaluate(ctx(TransactionType.TRANSFER, "499.99"))).isEmpty();
    }

    // ---------- FraudService (the strategy runner) ----------

    @Test
    void serviceThrowsOnAnyBlockAndAuditsTheAttempt() {
        FraudRule blocker = new StubRule(FraudVerdict.block("STUB_BLOCK", "nope"));
        FraudRule flagger = new StubRule(FraudVerdict.flag("STUB_FLAG", "hmm"));
        AuditService audit = mock(AuditService.class);
        FraudService service = new FraudService(List.of(flagger, blocker),
                mock(FraudFlagRepository.class), audit, mock(OutboxService.class));

        assertThatThrownBy(() -> service.assess(ctx(TransactionType.TRANSFER, "1.00")))
                .isInstanceOf(TransactionRejectedException.class)
                .hasMessage("nope");
        verify(audit).recordIndependently(eq(1L), any(), eq("ACCOUNT"), eq(7L), any());
    }

    @Test
    void serviceReturnsOnlyFlagsWhenNothingBlocks() {
        AuditService audit = mock(AuditService.class);
        FraudService service = new FraudService(
                List.of(new StubRule(FraudVerdict.flag("A", "a")), new StubRule(null), new StubRule(FraudVerdict.flag("B", "b"))),
                mock(FraudFlagRepository.class), audit, mock(OutboxService.class));

        assertThat(service.assess(ctx(TransactionType.TRANSFER, "1.00")))
                .extracting(FraudVerdict::ruleName).containsExactly("A", "B");
        verify(audit, never()).recordIndependently(any(), any(), any(), any(), any());
    }

    private record StubRule(FraudVerdict verdict) implements FraudRule {
        @Override public String name() { return "STUB"; }
        @Override public java.util.Optional<FraudVerdict> evaluate(FraudContext c) {
            return java.util.Optional.ofNullable(verdict);
        }
    }
}
