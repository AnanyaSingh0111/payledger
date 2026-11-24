package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.OutboxEvent;
import in.payledger.entity.OutboxStatus;
import in.payledger.entity.User;
import in.payledger.exception.InsufficientBalanceException;
import in.payledger.outbox.EventPublisher;
import in.payledger.outbox.OutboxRelay;
import in.payledger.repository.OutboxEventRepository;
import in.payledger.service.TransactionService;
import in.payledger.support.IntegrationTest;
import in.payledger.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static in.payledger.support.TestData.key;
import static in.payledger.support.TestData.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class OutboxTest {

    @Autowired TransactionService transactionService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxRelay relay;   // the real one, publishing through LoggingEventPublisher
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired TestData data;

    @BeforeEach
    void drainBacklogFromOtherTests() {
        while (relay.publishDueBatch() > 0) { }
    }

    private List<OutboxEvent> eventsFor(Long transactionId) {
        return outboxEventRepository.findByAggregateTypeAndAggregateId("TRANSACTION", transactionId.toString());
    }

    @Test
    void aCommittedTransactionLeavesExactlyOnePendingEventThatTheRelayPublishes() {
        User u = data.user();
        Account a = data.fundedAccount(u, "100.00");

        TransactionResponse t = transactionService.createTransaction(u.getId(), key("o"),
                CreateTransactionRequest.withdrawal(a.getId(), money("10.00")));

        assertThat(eventsFor(t.id())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(e.getEventType()).isEqualTo("TransactionCompleted");
            assertThat(e.getPayload()).contains("\"amount\":\"10.00\"");
        });

        relay.publishDueBatch();

        assertThat(eventsFor(t.id())).singleElement()
                .satisfies(e -> assertThat(e.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
    }

    @Test
    void aRolledBackTransactionLeavesNoEvent() {
        User u = data.user();
        Account a = data.fundedAccount(u, "5.00");
        Integer before = jdbc.queryForObject("select count(*) from outbox_events", Integer.class);

        assertThatThrownBy(() -> transactionService.createTransaction(u.getId(), key("o"),
                CreateTransactionRequest.withdrawal(a.getId(), money("10.00"))))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(before);
    }

    @Test
    void failingPublishesAreRetriedWithBackoffThenParkedAsFailed() {
        User u = data.user();
        Account a = data.fundedAccount(u, "100.00");
        TransactionResponse t = transactionService.createTransaction(u.getId(), key("o"),
                CreateTransactionRequest.withdrawal(a.getId(), money("1.00")));

        List<Long> attempted = new ArrayList<>();
        EventPublisher broken = event -> {
            attempted.add(event.getId());
            throw new IllegalStateException("broker down");
        };

        // a clock we move forward by hand, so backoff delays elapse instantly
        MutableClock clock = new MutableClock();
        // built by hand (not a Spring proxy), so each poll is wrapped in a transaction explicitly
        OutboxRelay relayWithBrokenBroker = new OutboxRelay(outboxEventRepository, broken, clock, 100, 3);
        Runnable poll = () -> tx.executeWithoutResult(s -> relayWithBrokenBroker.publishDueBatch());

        poll.run();
        OutboxEvent afterFirst = eventsFor(t.id()).getFirst();
        assertThat(afterFirst.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getLastError()).contains("broker down");

        // not due yet: backoff means an immediate second poll does not retry it
        attempted.clear();
        poll.run();
        assertThat(attempted).doesNotContain(afterFirst.getId());

        clock.advance(Duration.ofMinutes(15));
        poll.run();
        clock.advance(Duration.ofMinutes(15));
        poll.run();

        assertThat(eventsFor(t.id())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(e.getAttempts()).isEqualTo(3);
        });
    }

    /** Starts at the system time; tests move it forward. */
    static final class MutableClock extends Clock {
        private Clock delegate = Clock.systemDefaultZone();

        void advance(Duration d) { delegate = Clock.offset(delegate, d); }

        @Override public java.time.ZoneId getZone() { return delegate.getZone(); }
        @Override public Clock withZone(java.time.ZoneId zone) { return delegate.withZone(zone); }
        @Override public java.time.Instant instant() { return delegate.instant(); }
    }
}
