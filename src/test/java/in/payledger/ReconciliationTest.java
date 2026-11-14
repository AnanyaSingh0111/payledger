package in.payledger;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.MismatchDto;
import in.payledger.dto.ReconciliationReport;
import in.payledger.entity.*;
import in.payledger.repository.*;
import in.payledger.service.ReconciliationService;
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
class ReconciliationTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired TransactionService transactionService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;

    @Test
    void detectsACorruptedBalance() {
        User u = new User();
        u.setName("R");
        u.setEmail("r" + System.nanoTime() + "@x.com");
        u.setPasswordHash("x");
        u.setRole(Role.USER);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        u = userRepository.save(u);

        Account a = new Account();
        a.setUser(u);
        a.setName("Acc");
        a.setType(AccountType.values()[0]);
        a.setBalance(BigDecimal.ZERO);
        a.setCurrency("INR");
        a.setCreatedAt(LocalDateTime.now());
        a = accountRepository.save(a);
        final Long accountId = a.getId();

        CreateTransactionRequest open = new CreateTransactionRequest();
        open.setType(TransactionType.INCOME);
        open.setDestinationAccountId(accountId);
        open.setAmount(new BigDecimal("100.00"));
        transactionService.createTransaction(u.getId(), "rec-open-" + accountId, open);

        // 1. a correct account is NOT reported
        ReconciliationReport before = reconciliationService.run();
        assertTrue(before.mismatches().stream().noneMatch(m -> m.accountId().equals(accountId)));

        // 2. corrupt the stored balance by +5 behind the ledger's back
        Account stored = accountRepository.findById(accountId).get();
        stored.setBalance(stored.getBalance().add(new BigDecimal("5.00")));
        accountRepository.save(stored);

        // 3. now it IS reported, with the exact difference
        ReconciliationReport after = reconciliationService.run();
        MismatchDto found = after.mismatches().stream()
                .filter(m -> m.accountId().equals(accountId))
                .findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("5.00").compareTo(found.difference()));
        assertFalse(after.healthy());
    }
}