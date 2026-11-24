package in.payledger;

import in.payledger.dto.CreateAccountRequest;
import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.User;
import in.payledger.exception.ResourceAlreadyExistsException;
import in.payledger.repository.AccountRepository;
import in.payledger.service.AccountService;
import in.payledger.service.TransactionFacade;
import in.payledger.service.TransactionService;
import in.payledger.support.IntegrationTest;
import in.payledger.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static in.payledger.support.TestData.money;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real threads against real MySQL, all released at the same instant by a latch.
 * These are the race conditions the locking and idempotency design exists to prevent.
 */
@IntegrationTest
class ConcurrencyTest {

    @Autowired TransactionService transactionService;
    @Autowired TransactionFacade transactionFacade;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired TestData data;

    @Test
    void tenParallelRequestsToOpenAnInrWalletCreateExactlyOne() throws Exception {
        User user = data.user();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    accountService.createAccount(user.getId(), CreateAccountRequest.of("INR"));
                    created.incrementAndGet();
                } catch (ResourceAlreadyExistsException e) {
                    refused.incrementAndGet();   // "You already have a wallet in INR"
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // the existence check alone would let several through; UNIQUE(user_id, currency) stops them
        assertThat(created.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(9);
        assertThat(accountRepository.findByUserIdOrderByIdAsc(user.getId())).hasSize(1);
    }

    @Test
    void parallelWithdrawalsNeverOverdrawAndExactlyTenSucceed() throws Exception {
        User user = data.user();
        Account acc = data.fundedAccount(user, "1000.00");

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    transactionService.createTransaction(user.getId(), TestData.key("wd"),
                            CreateTransactionRequest.withdrawal(acc.getId(), money("100.00")));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        System.out.println("OVERDRAFT TEST -> succeeded=" + ok.get() + " failed=" + failed.get()
                + " finalBalance=" + data.balance(acc));

        assertThat(ok.get()).isEqualTo(10);
        assertThat(data.balance(acc)).isEqualByComparingTo("0.00");
        assertThat(data.ledgerBalance(acc)).isEqualByComparingTo("0.00");
    }

    @Test
    void twentyRetriesWithTheSameKeyMoveMoneyOnceAndAllGetTheSameAnswer() throws Exception {
        User user = data.user();
        Account acc = data.fundedAccount(user, "1000.00");
        String key = TestData.key("same");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> returnedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    TransactionResponse r = transactionFacade.create(user.getId(), key,
                            CreateTransactionRequest.withdrawal(acc.getId(), money("100.00")));
                    returnedIds.add(r.id());
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.out.println("IDEMPOTENCY ERROR: " + e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        System.out.println("IDEMPOTENCY TEST -> distinct ids=" + returnedIds + " errors=" + errors.get()
                + " finalBalance=" + data.balance(acc));

        // every caller, including the ones that lost the race, gets the same transaction back
        assertThat(errors.get()).isZero();
        assertThat(returnedIds).hasSize(1);
        assertThat(data.balance(acc)).isEqualByComparingTo("900.00");
    }

    @Test
    void thousandOpposingTransfersKeepExactBalancesAndNeverDeadlock() throws Exception {
        // one INR wallet per user, so the two wallets belong to two users
        User userA = data.user(), userB = data.user();
        Account a = data.fundedAccount(userA, "10000.00");
        Account b = data.fundedAccount(userB, "10000.00");

        int tasks = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < tasks; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                // even tasks A -> B, odd tasks B -> A: the classic deadlock pattern
                Long from = (n % 2 == 0) ? a.getId() : b.getId();
                Long to = (n % 2 == 0) ? b.getId() : a.getId();
                Long sender = (n % 2 == 0) ? userA.getId() : userB.getId();
                try {
                    transactionService.createTransaction(sender, "xfer-" + n + "-" + a.getId(),
                            CreateTransactionRequest.transfer(from, to, money("1.00")));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.out.println("FAILED: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
                return null;
            }));
        }

        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) f.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();

        System.out.println("TRANSFER TEST -> succeeded=" + ok.get() + " failed=" + failed.get()
                + " timeMs=" + ms + " throughput=" + (tasks * 1000L / Math.max(ms, 1)) + " tx/s"
                + " balA=" + data.balance(a) + " balB=" + data.balance(b));

        assertThat(failed.get()).isZero();
        // 500 each way, so both end exactly where they started, and the ledger agrees
        assertThat(data.balance(a)).isEqualByComparingTo("10000.00");
        assertThat(data.balance(b)).isEqualByComparingTo("10000.00");
        assertThat(data.ledgerBalance(a)).isEqualByComparingTo("10000.00");
        assertThat(data.ledgerBalance(b)).isEqualByComparingTo("10000.00");
    }
}
