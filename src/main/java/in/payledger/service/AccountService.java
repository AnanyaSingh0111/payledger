package in.payledger.service;

import in.payledger.dto.AccountResponse;
import in.payledger.dto.CreateAccountRequest;
import in.payledger.dto.CreateTransactionRequest;
import in.payledger.entity.Account;
import in.payledger.entity.TransactionType;
import in.payledger.entity.User;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionService transactionService;

    public AccountService(
            AccountRepository accountRepository,
            UserRepository userRepository,
            TransactionService transactionService) {

        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.transactionService = transactionService;
    }

    @Transactional
    public AccountResponse createAccount(
            Long userId,
            CreateAccountRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found with id: " + userId
                        )
                );

        BigDecimal initialBalance = request.getInitialBalance();

        if (initialBalance != null && initialBalance.signum() < 0) {
            throw new InvalidTransactionException(
                    "Initial balance cannot be negative"
            );
        }

        // The account always starts at zero. Any starting money enters through
        // the ledger as an "Opening balance" income transaction below.
        Account account = new Account();

        account.setUser(user);
        account.setName(request.getName());
        account.setType(request.getType());
        account.setBalance(BigDecimal.ZERO);
        account.setCurrency("INR");
        account.setCreatedAt(LocalDateTime.now());

        Account savedAccount = accountRepository.save(account);

        if (initialBalance != null && initialBalance.signum() > 0) {

            CreateTransactionRequest opening = new CreateTransactionRequest();
            opening.setType(TransactionType.INCOME);
            opening.setDestinationAccountId(savedAccount.getId());
            opening.setAmount(initialBalance);
            opening.setCategory("OPENING");
            opening.setDescription("Opening balance");

            // Runs in this same database transaction: the account, the
            // income transaction and its ledger entries commit together
            // or not at all.
            transactionService.createTransaction(
                    userId,
                    "opening-" + savedAccount.getId(),
                    opening
            );
        }

        Account fresh = accountRepository.findById(savedAccount.getId())
                .orElseThrow();

        return toResponse(fresh);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsForUser(
            Long userId) {

        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException(
                    "User not found with id: " + userId
            );
        }

        return accountRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Account not found with id: "
                                        + accountId
                        )
                );

        return toResponse(account);
    }

    private AccountResponse toResponse(Account account) {

        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt()
        );
    }
}