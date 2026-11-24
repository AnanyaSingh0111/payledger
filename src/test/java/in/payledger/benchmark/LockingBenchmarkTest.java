package in.payledger.benchmark;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.entity.Account;
import in.payledger.entity.User;
import in.payledger.repository.AccountRepository;
import in.payledger.service.TransactionService;
import in.payledger.support.IntegrationTest;
import in.payledger.support.TestData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static in.payledger.support.TestData.money;

/**
 * Same workload, four locking strategies. Not part of the normal build (it is slow and prints
 * numbers rather than asserting them). Run it with:
 *
 *   ./mvnw test -Dtest=LockingBenchmarkTest -Dsurefire.excludedGroups=
 *
 * Results are printed and written to target/locking-benchmark.md.
 */
@Tag("benchmark")
@IntegrationTest
class LockingBenchmarkTest {

    enum Strategy {
        /** read, check, write with no lock at all: the naive version, to show the bug */
        NO_LOCKING,
        /** @Version only, the loser of a conflict fails */
        OPTIMISTIC,
        /** @Version, and the loser retries until it wins */
        OPTIMISTIC_RETRY,
        /** SELECT ... FOR UPDATE, lowest id first */
        PESSIMISTIC,
        /** the real TransactionService: pessimistic locks plus ledger, fraud rules, audit and outbox */
        PRODUCTION_PATH
    }

    record Result(Strategy strategy, String scenario, int succeeded, int failed, long retries,
                  long ms, String finalState, boolean correct) { }

    @Autowired AccountRepository accountRepository;
    @Autowired TransactionService transactionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired TestData data;

    private TransactionTemplate tx;

    @Test
    void compareLockingStrategies() throws Exception {
        tx = new TransactionTemplate(txManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        List<Result> results = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            results.add(overdraft(s));
        }
        for (Strategy s : Strategy.values()) {
            results.add(opposingTransfers(s));
        }

        StringBuilder md = new StringBuilder()
                .append("| Strategy | Scenario | Succeeded | Failed | Retries | Time (ms) | Final state | Correct |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append("| ").append(r.strategy()).append(" | ").append(r.scenario())
                    .append(" | ").append(r.succeeded()).append(" | ").append(r.failed())
                    .append(" | ").append(r.retries()).append(" | ").append(r.ms())
                    .append(" | ").append(r.finalState()).append(" | ").append(r.correct() ? "yes" : "**NO**")
                    .append(" |\n");
        }
        System.out.println("\n" + md);
        Path out = Path.of("target", "locking-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
    }

    /** 50 parallel withdrawals of 100 from a balance of 1,000: exactly 10 may succeed. */
    private Result overdraft(Strategy s) throws Exception {
        User u = data.user();
        Account acc = data.fundedAccount(u, "1000.00");
        AtomicInteger ok = new AtomicInteger(), failed = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();

        long ms = race(50, 50, n -> {
            if (s == Strategy.PRODUCTION_PATH) {
                transactionService.createTransaction(u.getId(), TestData.key("b"),
                        CreateTransactionRequest.withdrawal(acc.getId(), money("100.00")));
            } else {
                withdraw(s, acc.getId(), money("100.00"), retries);
            }
        }, ok, failed);

        BigDecimal balance = currentBalance(acc.getId());
        // money that left = 100 per success; it must equal what the balance says
        BigDecimal expected = money("1000.00").subtract(money("100.00").multiply(BigDecimal.valueOf(ok.get())));
        boolean correct = ok.get() == 10 && balance.compareTo(expected) == 0;
        return new Result(s, "50 x withdraw 100 from 1,000", ok.get(), failed.get(), retries.get(), ms,
                "balance " + balance.toPlainString() + " after " + ok.get() + " withdrawals", correct);
    }

    /** 1,000 transfers of 1.00, half A to B and half B to A, on 32 threads. */
    private Result opposingTransfers(Strategy s) throws Exception {
        User u = data.user();
        Account a = data.fundedAccount(u, "10000.00");
        Account b = data.fundedAccount(u, "10000.00");
        AtomicInteger ok = new AtomicInteger(), failed = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();

        long ms = race(1000, 32, n -> {
            long from = n % 2 == 0 ? a.getId() : b.getId();
            long to = n % 2 == 0 ? b.getId() : a.getId();
            if (s == Strategy.PRODUCTION_PATH) {
                transactionService.createTransaction(u.getId(), TestData.key("b"),
                        CreateTransactionRequest.transfer(from, to, money("1.00")));
            } else {
                transfer(s, from, to, money("1.00"), retries);
            }
        }, ok, failed);

        BigDecimal total = currentBalance(a.getId()).add(currentBalance(b.getId()));
        boolean correct = total.compareTo(money("20000.00")) == 0;
        return new Result(s, "1,000 opposing transfers", ok.get(), failed.get(), retries.get(), ms,
                "total " + total.toPlainString() + " (must be 20000.00)", correct);
    }

    // ------------------------------------------------------------------ strategies

    private void withdraw(Strategy s, long accountId, BigDecimal amount, AtomicInteger retries) {
        move(s, accountId, null, amount, retries);
    }

    private void transfer(Strategy s, long from, long to, BigDecimal amount, AtomicInteger retries) {
        move(s, from, to, amount, retries);
    }

    private void move(Strategy s, long from, Long to, BigDecimal amount, AtomicInteger retries) {
        switch (s) {
            case NO_LOCKING -> tx.executeWithoutResult(st -> {
                // plain reads: two requests can both read the same old balance
                BigDecimal src = jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, from);
                if (src.compareTo(amount) < 0) throw new IllegalStateException("insufficient");
                jdbc.update("update accounts set balance = ? where id = ?", src.subtract(amount), from);
                if (to != null) {
                    BigDecimal dst = jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, to);
                    jdbc.update("update accounts set balance = ? where id = ?", dst.add(amount), to);
                }
            });
            case OPTIMISTIC -> optimisticOnce(from, to, amount);
            case OPTIMISTIC_RETRY -> {
                for (int attempt = 1; ; attempt++) {
                    try {
                        optimisticOnce(from, to, amount);
                        return;
                    } catch (IllegalStateException insufficient) {
                        throw insufficient;
                    } catch (RuntimeException conflict) {   // version conflict or deadlock
                        if (attempt == 200) throw conflict;
                        retries.incrementAndGet();
                        sleep(1 + (long) (Math.random() * 5));
                    }
                }
            }
            case PESSIMISTIC -> tx.executeWithoutResult(st -> {
                long first = to == null ? from : Math.min(from, to);
                Account a1 = accountRepository.findByIdForUpdate(first).orElseThrow();
                Account a2 = to == null ? null : accountRepository.findByIdForUpdate(Math.max(from, to)).orElseThrow();
                Account src = a1.getId() == from ? a1 : a2;
                Account dst = to == null ? null : (a1.getId() == from ? a2 : a1);
                if (src.getBalance().compareTo(amount) < 0) throw new IllegalStateException("insufficient");
                src.debit(amount);
                if (dst != null) dst.credit(amount);
            });
            default -> throw new IllegalArgumentException(s.name());
        }
    }

    /** @Version check happens at commit: UPDATE ... WHERE id = ? AND version = ? */
    private void optimisticOnce(long from, Long to, BigDecimal amount) {
        tx.executeWithoutResult(st -> {
            Account src = accountRepository.findById(from).orElseThrow();
            if (src.getBalance().compareTo(amount) < 0) throw new IllegalStateException("insufficient");
            src.debit(amount);
            if (to != null) accountRepository.findById(to).orElseThrow().credit(amount);
        });
    }

    // ------------------------------------------------------------------ harness

    interface Task { void run(int n) throws Exception; }

    private long race(int tasks, int threads, Task task, AtomicInteger ok, AtomicInteger failed) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    task.run(n);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
                return null;
            }));
        }
        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private BigDecimal currentBalance(long accountId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
