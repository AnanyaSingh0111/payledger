package in.payledger.support;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.AccountType;
import in.payledger.entity.Role;
import in.payledger.entity.User;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.repository.UserRepository;
import in.payledger.service.TransactionService;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds users and accounts for tests. Money always enters through a TOP_UP, never by writing
 * the balance column, so every test account stays consistent with the ledger.
 */
@TestComponent
public class TestData {

    private static final AtomicLong SEQ = new AtomicLong();

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionService transactionService;

    public TestData(UserRepository userRepository,
                    AccountRepository accountRepository,
                    LedgerEntryRepository ledgerEntryRepository,
                    TransactionService transactionService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionService = transactionService;
    }

    public User user() {
        User u = new User();
        u.setName("Test User");
        u.setEmail("user" + SEQ.incrementAndGet() + "-" + System.nanoTime() + "@test.local");
        u.setPasswordHash("not-used");
        u.setRole(Role.USER);
        return userRepository.save(u);
    }

    public Account account(User owner) {
        return account(owner, "INR");
    }

    public Account account(User owner, String currency) {
        Account a = new Account();
        a.setUser(owner);
        a.setName("Wallet " + SEQ.incrementAndGet());
        a.setType(AccountType.WALLET);
        a.setCurrency(currency);
        a.setBalance(BigDecimal.ZERO);
        return accountRepository.save(a);
    }

    public Account fundedAccount(User owner, String amount) {
        Account a = account(owner);
        topUp(owner, a, amount);
        return a;
    }

    public TransactionResponse topUp(User owner, Account account, String amount) {
        return transactionService.createTransaction(owner.getId(), key("topup"),
                CreateTransactionRequest.topUp(account.getId(), new BigDecimal(amount)));
    }

    public BigDecimal balance(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    public BigDecimal ledgerBalance(Account account) {
        return ledgerEntryRepository.netForAccount(account.getId());
    }

    public static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
