package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.entity.*;
import in.payledger.repository.*;
import in.payledger.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "spring.datasource.url=jdbc:mysql://localhost:3306/payledger_test?createDatabaseIfNotExist=true",
        "spring.jpa.show-sql=false"
})
class ConcurrencyTest {

    @Autowired TransactionService transactionService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;

    // ---------- helpers ----------

    private User newUser() {
        User u = new User();
        u.setName("T");
        u.setEmail("t" + System.nanoTime() + "@x.com");
        u.setPasswordHash("x");
        u.setRole(Role.USER);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    private Account newAccount(User u, String balance) {
        Account a = new Account();
        a.setUser(u);
        a.setName("Acc");
        a.setType(AccountType.values()[0]);
        a.setBalance(new BigDecimal(balance));
        a.setCurrency("INR");
        a.setCreatedAt(LocalDateTime.now());
        return accountRepository.save(a);
    }

    private CreateTransactionRequest expense(Long accountId, String amount) {
        CreateTransactionRequest r = new CreateTransactionRequest();
        r.setType(TransactionType.EXPENSE);
        r.setSourceAccountId(accountId);
        r.setAmount(new BigDecimal(amount));
        return r;
    }

    private CreateTransactionRequest transfer(Long from, Long to, String amount) {
        CreateTransactionRequest r = new CreateTransactionRequest();
        r.setType(TransactionType.TRANSFER);
        r.setSourceAccountId(from);
        r.setDestinationAccountId(to);
        r.setAmount(new BigDecimal(amount));
        return r;
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).get().getBalance();
    }

    // ---------- Test 1: no overdraft ----------

    @Test
    void parallelExpensesNeverOverdrawAndExactlyTenSucceed() throws Exception {
        User user = newUser();
        Account acc = newAccount(user, "1000.00");

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String key = "key-" + i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    transactionService.createTransaction(
                            user.getId(), key, expense(acc.getId(), "100.00"));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }));
        }

        long t0 = System.nanoTime();
        start.countDown();                       // release all 50 threads at once
        for (Future<?> f : futures) f.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();

        System.out.println("EXPENSE TEST -> succeeded=" + ok.get()
                + " failed=" + failed.get() + " timeMs=" + ms
                + " finalBalance=" + balanceOf(acc.getId()));

        assertEquals(10, ok.get());
        assertEquals(0, new BigDecimal("0.00").compareTo(balanceOf(acc.getId())));
    }

    // ---------- Test 2: idempotency ----------

    @Test
    void sameIdempotencyKeyDebitsOnlyOnce() throws Exception {
        User user = newUser();
        Account acc = newAccount(user, "1000.00");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    transactionService.createTransaction(
                            user.getId(), "same-key", expense(acc.getId(), "100.00"));
                } catch (Exception ignored) { }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        System.out.println("IDEMPOTENCY TEST -> finalBalance=" + balanceOf(acc.getId()));
        assertEquals(0, new BigDecimal("900.00").compareTo(balanceOf(acc.getId())));
    }

    // ---------- Test 3: 1,000 opposing transfers, no deadlock, money conserved ----------

    @Test
    void thousandOpposingTransfersKeepExactBalancesAndNeverDeadlock() throws Exception {
        User user = newUser();
        Account a = newAccount(user, "10000.00");
        Account b = newAccount(user, "10000.00");

        int tasks = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < tasks; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // even tasks: A -> B, odd tasks: B -> A (the deadlock-prone pattern)
                    Long from = (n % 2 == 0) ? a.getId() : b.getId();
                    Long to   = (n % 2 == 0) ? b.getId() : a.getId();
                    transactionService.createTransaction(
                            user.getId(), "xfer-" + n, transfer(from, to, "1.00"));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.out.println("FAILED: " + e.getClass().getSimpleName()
                            + " " + e.getMessage());
                }
            }));
        }

        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) f.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();

        BigDecimal balA = balanceOf(a.getId());
        BigDecimal balB = balanceOf(b.getId());

        System.out.println("TRANSFER TEST -> succeeded=" + ok.get() + " failed=" + failed.get()
                + " timeMs=" + ms + " throughput=" + (tasks * 1000L / Math.max(ms, 1)) + " tx/s"
                + " balA=" + balA + " balB=" + balB);

        assertEquals(0, failed.get());
        // 500 sent each way, so both accounts end exactly where they started
        assertEquals(0, new BigDecimal("10000.00").compareTo(balA));
        assertEquals(0, new BigDecimal("10000.00").compareTo(balB));
        // total money is conserved
        assertEquals(0, new BigDecimal("20000.00").compareTo(balA.add(balB)));
    }
}