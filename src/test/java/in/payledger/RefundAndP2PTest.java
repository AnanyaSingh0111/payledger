package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionFilter;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.TransactionStatus;
import in.payledger.entity.TransactionType;
import in.payledger.entity.User;
import in.payledger.exception.AccountFrozenException;
import in.payledger.exception.IdempotencyConflictException;
import in.payledger.exception.InsufficientBalanceException;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.entity.AccountStatus;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.service.AccountService;
import in.payledger.service.TransactionFacade;
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

@IntegrationTest
class RefundAndP2PTest {

    @Autowired TransactionService transactionService;
    @Autowired TransactionFacade transactionFacade;
    @Autowired AccountService accountService;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TestData data;

    private TransactionResponse pay(User from, Account src, Account dst, String amount) {
        return transactionService.createTransaction(from.getId(), key("pay"),
                CreateTransactionRequest.transfer(src.getId(), dst.getId(), money(amount)));
    }

    @Test
    void transferBetweenUsersThenRefundRestoresBothBalances() {
        User alice = data.user(), bob = data.user();
        Account accA = data.fundedAccount(alice, "100.00");
        Account accB = data.account(bob);

        TransactionResponse payment = pay(alice, accA, accB, "40.00");
        assertThat(data.balance(accA)).isEqualByComparingTo("60.00");
        assertThat(data.balance(accB)).isEqualByComparingTo("40.00");

        // the payer cannot refund their own payment
        assertThatThrownBy(() -> transactionService.refundTransaction(alice.getId(), payment.id(), key("wrong")))
                .isInstanceOf(ResourceNotFoundException.class);

        // the receiver can
        String refundKey = key("refund");
        TransactionResponse refund = transactionService.refundTransaction(bob.getId(), payment.id(), refundKey);

        assertThat(refund.type()).isEqualTo(TransactionType.REFUND);
        assertThat(refund.refundOfTransactionId()).isEqualTo(payment.id());
        assertThat(data.balance(accA)).isEqualByComparingTo("100.00");
        assertThat(data.balance(accB)).isEqualByComparingTo("0.00");
        assertThat(ledgerEntryRepository.findByTransactionId(refund.id())).hasSize(2);
        assertThat(transactionService.getTransactionById(alice.getId(), payment.id()).status())
                .isEqualTo(TransactionStatus.REFUNDED);

        // a second refund with a different key is rejected
        assertThatThrownBy(() -> transactionService.refundTransaction(bob.getId(), payment.id(), key("again")))
                .isInstanceOf(InvalidTransactionException.class);

        // the same key replays the original refund and moves no money
        TransactionResponse replay = transactionService.refundTransaction(bob.getId(), payment.id(), refundKey);
        assertThat(replay.id()).isEqualTo(refund.id());
        assertThat(data.balance(accA)).isEqualByComparingTo("100.00");
    }

    @Test
    void tenConcurrentRefundsOfOneTransferSucceedExactlyOnce() throws Exception {
        User alice = data.user(), bob = data.user();
        Account accA = data.fundedAccount(alice, "100.00");
        Account accB = data.fundedAccount(bob, "500.00");
        TransactionResponse payment = pay(alice, accA, accB, "40.00");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    transactionFacade.refund(bob.getId(), payment.id(), key("r"));
                    ok.incrementAndGet();
                } catch (InvalidTransactionException expected) {
                    // "already refunded"
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(data.balance(accA)).isEqualByComparingTo("100.00");
        assertThat(data.balance(accB)).isEqualByComparingTo("500.00");
    }

    @Test
    void cannotSpendFromSomeoneElsesAccount() {
        User alice = data.user(), mallory = data.user();
        Account victim = data.fundedAccount(alice, "50.00");
        Account thief = data.account(mallory);

        assertThatThrownBy(() -> transactionService.createTransaction(mallory.getId(), key("steal"),
                CreateTransactionRequest.transfer(victim.getId(), thief.getId(), money("10.00"))))
                .isInstanceOf(ResourceNotFoundException.class);

        // nor top up someone else's wallet out of thin air, nor withdraw from it
        assertThatThrownBy(() -> transactionService.createTransaction(mallory.getId(), key("w"),
                CreateTransactionRequest.withdrawal(victim.getId(), money("10.00"))))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(data.balance(victim)).isEqualByComparingTo("50.00");
    }

    @Test
    void refundFailsWhenTheReceiverAlreadySpentTheMoney() {
        User alice = data.user(), bob = data.user();
        Account accA = data.fundedAccount(alice, "40.00");
        Account accB = data.account(bob);
        TransactionResponse payment = pay(alice, accA, accB, "40.00");

        transactionService.createTransaction(bob.getId(), key("spend"),
                CreateTransactionRequest.withdrawal(accB.getId(), money("40.00")));

        assertThatThrownBy(() -> transactionService.refundTransaction(bob.getId(), payment.id(), key("late")))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void transfersBetweenDifferentCurrenciesAreRejected() {
        User u = data.user();
        Account inr = data.fundedAccount(u, "100.00");
        Account usd = data.account(u, "USD");

        assertThatThrownBy(() -> pay(u, inr, usd, "10.00"))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void reusingAKeyForADifferentPaymentIsAConflict() {
        User u = data.user();
        Account a = data.fundedAccount(u, "100.00");
        String k = key("k");

        transactionService.createTransaction(u.getId(), k, CreateTransactionRequest.withdrawal(a.getId(), money("10.00")));
        assertThatThrownBy(() -> transactionService.createTransaction(u.getId(), k,
                CreateTransactionRequest.withdrawal(a.getId(), money("20.00"))))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(data.balance(a)).isEqualByComparingTo("90.00");
    }

    @Test
    void reservedSystemKeysAreRejected() {
        User u = data.user();
        Account a = data.fundedAccount(u, "100.00");
        assertThatThrownBy(() -> transactionService.createTransaction(u.getId(), "sys:opening:" + a.getId(),
                CreateTransactionRequest.withdrawal(a.getId(), money("1.00"))))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void frozenAccountsCanNeitherSendNorReceive() {
        User alice = data.user(), bob = data.user();
        Account accA = data.fundedAccount(alice, "100.00");
        Account accB = data.fundedAccount(bob, "100.00");

        accountService.setStatus(null, accB.getId(), AccountStatus.FROZEN, "suspicious");

        assertThatThrownBy(() -> pay(alice, accA, accB, "10.00")).isInstanceOf(AccountFrozenException.class);
        assertThatThrownBy(() -> pay(bob, accB, accA, "10.00")).isInstanceOf(AccountFrozenException.class);

        accountService.setStatus(null, accB.getId(), AccountStatus.ACTIVE, "cleared");
        pay(alice, accA, accB, "10.00");
        assertThat(data.balance(accB)).isEqualByComparingTo("110.00");
    }

    @Test
    void historyFiltersByTypeAndAmount() {
        User u = data.user();
        Account a = data.fundedAccount(u, "1000.00");
        Account b = data.account(data.user());
        pay(u, a, b, "5.00");
        pay(u, a, b, "500.00");
        transactionService.createTransaction(u.getId(), key("w"),
                CreateTransactionRequest.withdrawal(a.getId(), money("7.00")));

        var transfers = transactionService.search(u.getId(),
                new TransactionFilter(a.getId(), TransactionType.TRANSFER, null, null, null, null, null),
                0, 20, "amount", "asc");
        assertThat(transfers.content()).extracting(TransactionResponse::amount)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(money("5.00"), money("500.00"));

        var big = transactionService.search(u.getId(),
                new TransactionFilter(null, null, null, null, null, money("100.00"), null), 0, 20, "createdAt", "desc");
        // the 1000 top-up and the 500 transfer
        assertThat(big.totalElements()).isEqualTo(2);
    }
}
