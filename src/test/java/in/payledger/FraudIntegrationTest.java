package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.FraudFlag;
import in.payledger.entity.User;
import in.payledger.exception.TransactionRejectedException;
import in.payledger.repository.AuditLogRepository;
import in.payledger.repository.FraudFlagRepository;
import in.payledger.service.TransactionService;
import in.payledger.support.IntegrationTest;
import in.payledger.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static in.payledger.support.TestData.key;
import static in.payledger.support.TestData.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Default rules: daily limit 200,000 (block), large amount 50,000 (flag). */
@IntegrationTest
class FraudIntegrationTest {

    @Autowired TransactionService transactionService;
    @Autowired FraudFlagRepository fraudFlagRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired TestData data;

    @Test
    void largeTransfersSucceedButAreFlaggedForReview() {
        User u = data.user();
        Account a = data.fundedAccount(u, "80000.00");
        Account b = data.account(data.user());

        TransactionResponse t = transactionService.createTransaction(u.getId(), key("big"),
                CreateTransactionRequest.transfer(a.getId(), b.getId(), money("60000.00")));

        assertThat(data.balance(b)).isEqualByComparingTo("60000.00");
        assertThat(fraudFlagRepository.findByTransactionId(t.id()))
                .extracting(FraudFlag::getRuleName).contains("LARGE_AMOUNT");
    }

    @Test
    void dailyLimitBlocksAndNoMoneyMovesButTheAttemptIsAudited() {
        User u = data.user();
        Account a = data.fundedAccount(u, "300000.00");

        transactionService.createTransaction(u.getId(), key("w1"),
                CreateTransactionRequest.withdrawal(a.getId(), money("150000.00")));

        assertThatThrownBy(() -> transactionService.createTransaction(u.getId(), key("w2"),
                CreateTransactionRequest.withdrawal(a.getId(), money("60000.00"))))
                .isInstanceOf(TransactionRejectedException.class)
                .hasMessageContaining("Daily outgoing limit");

        assertThat(data.balance(a)).isEqualByComparingTo("150000.00");
        // the blocked attempt survived the rollback because it was audited in its own transaction
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc("ACCOUNT", a.getId().toString()))
                .anyMatch(log -> log.getAction().equals("TRANSACTION_BLOCKED"));
    }

    /**
     * The interesting one. 10 parallel transfers of 30,000 against a 200,000 daily limit:
     * exactly 6 may succeed (180,000). This only holds because the rule runs after the row lock
     * is taken AND the transaction is READ COMMITTED. Under REPEATABLE READ each request would
     * sum a snapshot taken before it waited for the lock, see 0 sent today, and all 10 would pass.
     */
    @Test
    void dailyLimitHoldsUnderConcurrency() throws Exception {
        User u = data.user();
        Account a = data.fundedAccount(u, "500000.00");
        Account b = data.account(data.user());

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    transactionService.createTransaction(u.getId(), key("par"),
                            CreateTransactionRequest.transfer(a.getId(), b.getId(), money("30000.00")));
                    ok.incrementAndGet();
                } catch (TransactionRejectedException e) {
                    blocked.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        System.out.println("DAILY LIMIT CONCURRENCY -> succeeded=" + ok.get() + " blocked=" + blocked.get());
        assertThat(ok.get()).isEqualTo(6);
        assertThat(blocked.get()).isEqualTo(4);
        assertThat(data.balance(b)).isEqualByComparingTo("180000.00");
    }
}
