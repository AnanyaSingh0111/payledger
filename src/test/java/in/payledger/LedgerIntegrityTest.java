package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.EntryDirection;
import in.payledger.entity.LedgerEntry;
import in.payledger.entity.User;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.service.TransactionService;
import in.payledger.support.IntegrationTest;
import in.payledger.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static in.payledger.support.TestData.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The double-entry invariants hold under concurrent load, and the database enforces append-only. */
@IntegrationTest
class LedgerIntegrityTest {

    @Autowired TransactionService transactionService;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestData data;

    @Test
    void threeHundredConcurrentRandomTransfersKeepEveryBalanceEqualToItsLedger() throws Exception {
        // five wallets across three users, so transfers cross user boundaries too
        User u1 = data.user(), u2 = data.user(), u3 = data.user();
        List<Account> accounts = List.of(
                data.fundedAccount(u1, "5000.00"), data.fundedAccount(u1, "5000.00"),
                data.fundedAccount(u2, "5000.00"), data.fundedAccount(u2, "5000.00"),
                data.fundedAccount(u3, "5000.00"));
        BigDecimal totalBefore = sumBalances(accounts);

        int tasks = 300;
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        Random random = new Random(42);   // fixed seed: the same workload every run

        for (int i = 0; i < tasks; i++) {
            Account from = accounts.get(random.nextInt(accounts.size()));
            Account to;
            do {
                to = accounts.get(random.nextInt(accounts.size()));
            } while (to.getId().equals(from.getId()));
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(20_000), 2);   // 0.01 .. 200.00
            Account src = from, dst = to;

            futures.add(pool.submit(() -> {
                start.await();
                try {
                    transactionService.createTransaction(src.getUser().getId(), TestData.key("li"),
                            CreateTransactionRequest.transfer(src.getId(), dst.getId(), amount));
                } catch (Exception ignored) {
                    // an occasional insufficient balance is fine; the invariants must hold regardless
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // 1. no money created or destroyed: transfers only move it around
        assertThat(sumBalances(accounts)).isEqualByComparingTo(totalBefore);

        // 2. every stored balance equals the sum of its ledger entries, and none went negative
        for (Account a : accounts) {
            assertThat(data.balance(a)).isEqualByComparingTo(data.ledgerBalance(a));
            assertThat(data.balance(a).signum()).isGreaterThanOrEqualTo(0);
        }

        // 3. the whole ledger nets to zero, and so does every single transaction
        assertThat(ledgerEntryRepository.netAll()).isEqualByComparingTo("0");
        assertThat(ledgerEntryRepository.unbalancedTransactionIds()).isEmpty();
    }

    @Test
    void everyTransactionWritesExactlyOneDebitAndOneCredit() {
        User u = data.user();
        Account a = data.fundedAccount(u, "100.00");
        Account b = data.account(u);

        TransactionResponse t = transactionService.createTransaction(u.getId(), TestData.key("t"),
                CreateTransactionRequest.transfer(a.getId(), b.getId(), money("30.00")));

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(t.id());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(EntryDirection.DEBIT, EntryDirection.CREDIT);
        assertThat(entries).allSatisfy(e -> assertThat(e.getAmount()).isEqualByComparingTo("30.00"));
    }

    @Test
    void databaseRejectsUpdatesAndDeletesOnTheLedger() {
        User u = data.user();
        Account a = data.fundedAccount(u, "10.00");
        Long entryId = jdbc.queryForObject(
                "select id from ledger_entries where account_id = ? limit 1", Long.class, a.getId());

        // even raw SQL that bypasses the application is stopped by the triggers
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 999 where id = ?", entryId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where id = ?", entryId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_logs"))
                .hasMessageContaining("append-only");
    }

    @Test
    void databaseRejectsANegativeBalanceEvenFromRawSql() {
        User u = data.user();
        Account a = data.fundedAccount(u, "10.00");

        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -1 where id = ?", a.getId()))
                .hasMessageContaining("ck_accounts_balance_non_negative");
    }

    private BigDecimal sumBalances(List<Account> accounts) {
        return accounts.stream().map(data::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
