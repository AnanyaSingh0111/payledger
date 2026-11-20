package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.*;
import in.payledger.exception.InsufficientBalanceException;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.repository.UserRepository;
import in.payledger.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "spring.datasource.url=jdbc:mysql://localhost:3306/payledger_test?createDatabaseIfNotExist=true",
        "spring.jpa.show-sql=false"
})
class RefundAndP2PTest {

    @Autowired TransactionService transactionService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    private User newUser() {
        User u = new User();
        u.setName("U");
        u.setEmail("u" + System.nanoTime() + "@x.com");
        u.setPasswordHash("x");
        u.setRole(Role.USER);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    private Account newAccount(User u) {
        Account a = new Account();
        a.setUser(u);
        a.setName("Acc");
        a.setType(AccountType.values()[0]);
        a.setBalance(BigDecimal.ZERO);
        a.setCurrency("INR");
        a.setCreatedAt(LocalDateTime.now());
        return accountRepository.save(a);
    }

    private CreateTransactionRequest req(TransactionType t, Long src, Long dst, String amount) {
        CreateTransactionRequest r = new CreateTransactionRequest();
        r.setType(t);
        r.setSourceAccountId(src);
        r.setDestinationAccountId(dst);
        r.setAmount(new BigDecimal(amount));
        return r;
    }

    private String key(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private BigDecimal balance(Long accountId) {
        return accountRepository.findById(accountId).get().getBalance();
    }

    private void fund(User u, Account a, String amount) {
        transactionService.createTransaction(u.getId(), key("fund"),
                req(TransactionType.INCOME, null, a.getId(), amount));
    }

    @Test
    void transferBetweenUsersThenRefundRestoresBothBalances() {
        User a = newUser();
        User b = newUser();
        Account accA = newAccount(a);
        Account accB = newAccount(b);
        fund(a, accA, "100.00");

        TransactionResponse payment = transactionService.createTransaction(
                a.getId(), key("pay"), req(TransactionType.TRANSFER, accA.getId(), accB.getId(), "40.00"));

        assertEquals(0, new BigDecimal("60.00").compareTo(balance(accA.getId())));
        assertEquals(0, new BigDecimal("40.00").compareTo(balance(accB.getId())));

        // the sender cannot refund their own payment
        assertThrows(ResourceNotFoundException.class, () ->
                transactionService.refundTransaction(a.getId(), payment.getId(), key("wrong")));

        // the receiver can
        String refundKey = key("refund");
        TransactionResponse refund =
                transactionService.refundTransaction(b.getId(), payment.getId(), refundKey);

        assertEquals(payment.getId(), refund.getRefundOfTransactionId());
        assertEquals(0, new BigDecimal("100.00").compareTo(balance(accA.getId())));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance(accB.getId())));
        assertEquals(2, ledgerEntryRepository.findByTransactionId(refund.getId()).size());

        // a second refund with a different key is rejected
        assertThrows(InvalidTransactionException.class, () ->
                transactionService.refundTransaction(b.getId(), payment.getId(), key("again")));

        // the same key replays the original refund and moves no money
        TransactionResponse replay =
                transactionService.refundTransaction(b.getId(), payment.getId(), refundKey);
        assertEquals(refund.getId(), replay.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(balance(accA.getId())));
    }

    @Test
    void cannotSpendFromSomeoneElsesAccount() {
        User a = newUser();
        User b = newUser();
        Account accA = newAccount(a);
        Account accB = newAccount(b);
        fund(b, accB, "50.00");

        assertThrows(ResourceNotFoundException.class, () ->
                transactionService.createTransaction(
                        a.getId(), key("steal"),
                        req(TransactionType.TRANSFER, accB.getId(), accA.getId(), "10.00")));

        assertEquals(0, new BigDecimal("50.00").compareTo(balance(accB.getId())));
    }

    @Test
    void refundFailsWhenTheReceiverAlreadySpentTheMoney() {
        User a = newUser();
        User b = newUser();
        Account accA = newAccount(a);
        Account accB = newAccount(b);
        fund(a, accA, "40.00");

        TransactionResponse payment = transactionService.createTransaction(
                a.getId(), key("pay"), req(TransactionType.TRANSFER, accA.getId(), accB.getId(), "40.00"));

        transactionService.createTransaction(
                b.getId(), key("spend"), req(TransactionType.EXPENSE, accB.getId(), null, "40.00"));

        assertThrows(InsufficientBalanceException.class, () ->
                transactionService.refundTransaction(b.getId(), payment.getId(), key("late")));
    }
}