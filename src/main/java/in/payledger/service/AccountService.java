package in.payledger.service;

import in.payledger.audit.AuditAction;
import in.payledger.audit.AuditService;
import in.payledger.dto.AccountDetailResponse;
import in.payledger.dto.AccountResponse;
import in.payledger.dto.CreateAccountRequest;
import in.payledger.dto.LedgerEntryResponse;
import in.payledger.dto.PageResponse;
import in.payledger.entity.Account;
import in.payledger.entity.AccountStatus;
import in.payledger.entity.AccountType;
import in.payledger.entity.User;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceAlreadyExistsException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.exception.UserDisabledException;
import in.payledger.outbox.OutboxService;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          TransactionService transactionService,
                          AuditService auditService,
                          OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountResponse createAccount(Long userId, CreateAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        if (!user.isActive()) {
            throw new UserDisabledException("User account is disabled");
        }

        // One wallet per user per currency. The check gives a clear message; the
        // UNIQUE(user_id, currency) constraint decides if two requests race past it.
        String currency = validCurrency(request.currency());
        if (accountRepository.existsByUserIdAndCurrency(userId, currency)) {
            throw new ResourceAlreadyExistsException("You already have a wallet in " + currency);
        }

        Account account = new Account();
        account.setUser(user);
        account.setName(request.name() == null || request.name().isBlank()
                ? currency + " Wallet" : request.name().trim());
        account.setType(request.type() != null ? request.type() : AccountType.WALLET);
        account.setCurrency(currency);
        account.setBalance(BigDecimal.ZERO);   // money only ever enters through the ledger

        Account saved;
        try {
            saved = accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new ResourceAlreadyExistsException("You already have a wallet in " + currency);
        }

        auditService.record(userId, AuditAction.ACCOUNT_CREATED, "ACCOUNT", saved.getId(),
                saved.getType() + " " + saved.getCurrency() + " '" + saved.getName() + "'");

        BigDecimal initial = request.initialBalance();
        if (initial != null && initial.signum() > 0) {
            // Same database transaction: the account and its opening top-up commit together or not at all.
            transactionService.recordOpeningBalance(userId, saved.getId(), initial);
        }
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsForUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return accountRepository.findByUserIdOrderByIdAsc(userId).stream().map(AccountResponse::from).toList();
    }

    /** The account plus its balance recomputed from the ledger, so a client can verify both agree. */
    @Transactional(readOnly = true)
    public AccountDetailResponse getAccount(Long userId, Long accountId) {
        Account account = ownedAccount(userId, accountId);
        BigDecimal ledger = ledgerEntryRepository.netForAccount(accountId);
        return new AccountDetailResponse(AccountResponse.from(account), ledger,
                account.getBalance().compareTo(ledger) == 0);
    }

    /** Account statement: the ledger entries that make up the balance, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> getStatement(Long userId, Long accountId, int page, int size) {
        ownedAccount(userId, accountId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(ledgerEntryRepository.findByAccountId(accountId, pageable), LedgerEntryResponse::from);
    }

    /**
     * Freezing takes the same row lock as a transfer. It therefore waits for any in-flight
     * transfer on the account to finish, and every transfer after it sees FROZEN.
     */
    @Transactional
    public AccountResponse setStatus(Long adminId, Long accountId, AccountStatus status, String reason) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + accountId));
        if (account.getStatus() == status) {
            return AccountResponse.from(account);
        }
        account.setStatus(status);

        AuditAction action = status == AccountStatus.FROZEN ? AuditAction.ACCOUNT_FROZEN : AuditAction.ACCOUNT_UNFROZEN;
        auditService.record(adminId, action, "ACCOUNT", accountId, reason);
        outboxService.enqueue("ACCOUNT", accountId, status == AccountStatus.FROZEN ? "AccountFrozen" : "AccountUnfrozen",
                Map.of("accountId", accountId, "ownerId", account.getUser().getId(), "reason", reason == null ? "" : reason));
        return AccountResponse.from(account);
    }

    private Account ownedAccount(Long userId, Long accountId) {
        return accountRepository.findById(accountId)
                .filter(a -> a.isOwnedBy(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Account not found for this user"));
    }

    private static String validCurrency(String code) {
        if (code == null || code.isBlank()) {
            return "INR";
        }
        try {
            return Currency.getInstance(code).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new InvalidTransactionException("Unknown currency code: " + code);
        }
    }
}
