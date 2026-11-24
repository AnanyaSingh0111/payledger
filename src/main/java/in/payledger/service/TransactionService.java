package in.payledger.service;

import in.payledger.audit.AuditAction;
import in.payledger.audit.AuditService;
import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.PageResponse;
import in.payledger.dto.TransactionFilter;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.EntryDirection;
import in.payledger.entity.LedgerEntry;
import in.payledger.entity.Transaction;
import in.payledger.entity.TransactionStatus;
import in.payledger.entity.TransactionType;
import in.payledger.exception.AccountFrozenException;
import in.payledger.exception.IdempotencyConflictException;
import in.payledger.exception.InsufficientBalanceException;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.fraud.FraudContext;
import in.payledger.fraud.FraudService;
import in.payledger.fraud.FraudVerdict;
import in.payledger.outbox.OutboxService;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Every money movement follows the same steps inside ONE database transaction:
 *
 *   1. idempotency check (replay the stored result if this key was already used)
 *   2. validate the request shape
 *   3. lock every involved account with SELECT ... FOR UPDATE, lowest id first
 *   4. re-check idempotency (a concurrent request with the same key may have committed while we waited)
 *   5. ownership, frozen-account and currency checks
 *   6. fraud rules (may BLOCK, or return FLAGs)
 *   7. balance check, then update the balances
 *   8. insert the transaction row, two ledger entries, fraud flags, an audit row and an outbox event
 *
 * Any exception rolls back all of it. Isolation is READ COMMITTED so the checks in steps 4 and 6
 * see rows committed while this transaction was waiting for its locks (see DailyLimitRule).
 *
 * Callers that face the network should go through {@link TransactionFacade}, which turns an
 * idempotency-key race into a replay and retries lock timeouts.
 */
@Service
public class TransactionService {

    static final String SYSTEM_KEY_PREFIX = "sys:";

    private static final List<String> SORTABLE_FIELDS = List.of("createdAt", "amount", "id");

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FraudService fraudService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final Clock clock;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountRepository accountRepository,
                              LedgerEntryRepository ledgerEntryRepository,
                              FraudService fraudService,
                              AuditService auditService,
                              OutboxService outboxService,
                              Clock clock) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.fraudService = fraudService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Top-up, withdrawal, transfer
    // ------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse createTransaction(Long userId, String idempotencyKey, CreateTransactionRequest request) {
        validateClientKey(idempotencyKey);
        return execute(userId, idempotencyKey, request);
    }

    /**
     * Records an account's opening balance as a TOP_UP. Uses a reserved "sys:" key that clients
     * are not allowed to send, so nobody can pre-claim the key and block account creation.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse recordOpeningBalance(Long userId, Long accountId, BigDecimal amount) {
        CreateTransactionRequest opening = new CreateTransactionRequest(
                TransactionType.TOP_UP, null, accountId, amount, "OPENING", "Opening balance");
        return execute(userId, SYSTEM_KEY_PREFIX + "opening:" + accountId, opening);
    }

    private TransactionResponse execute(Long userId, String key, CreateTransactionRequest request) {

        // 1. Idempotency fast path: a retried request returns the original result.
        Optional<TransactionResponse> replay = replayIfProcessed(userId, key, request);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 2. Shape of the request.
        TransactionType type = request.type();
        Long srcId = request.sourceAccountId();
        Long dstId = request.destinationAccountId();
        switch (type) {
            case TOP_UP -> require(srcId == null && dstId != null,
                    "A top-up needs a destinationAccountId and no sourceAccountId");
            case WITHDRAWAL -> require(srcId != null && dstId == null,
                    "A withdrawal needs a sourceAccountId and no destinationAccountId");
            case TRANSFER -> {
                require(srcId != null && dstId != null,
                        "A transfer needs both sourceAccountId and destinationAccountId");
                require(!srcId.equals(dstId), "Source and destination accounts must be different");
            }
            case REFUND -> throw new InvalidTransactionException(
                    "Refunds are created with POST /transactions/{id}/refund");
        }

        // 3. Lock every account this transaction touches, in ascending id order (no deadlocks).
        Map<Long, Account> locked = lockInOrder(srcId, dstId);
        Account source = srcId != null ? locked.get(srcId) : null;
        Account destination = dstId != null ? locked.get(dstId) : null;

        // 4. Re-check idempotency now that we hold the locks. With READ COMMITTED this sees a
        //    same-key request that committed while we were waiting, so we replay instead of
        //    failing on the unique constraint.
        replay = replayIfProcessed(userId, key, request);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 5. Ownership, status and currency. Money can only leave (or, for a top-up, enter)
        //    an account the caller owns; a transfer may credit someone else's account.
        Account owned = source != null ? source : destination;
        if (!owned.isOwnedBy(userId)) {
            throw new ResourceNotFoundException("Account not found for this user");
        }
        ensureNotFrozen(source, destination);
        if (source != null && destination != null
                && !Objects.equals(source.getCurrency(), destination.getCurrency())) {
            throw new InvalidTransactionException("Accounts must use the same currency");
        }

        BigDecimal amount = request.amount();

        // 6. Fraud rules. A BLOCK throws here, before any balance changes.
        List<FraudVerdict> flags = fraudService.assess(
                new FraudContext(userId, type, source, destination, amount, LocalDateTime.now(clock)));

        // 7. Move the money.
        if (source != null) {
            ensureSufficientBalance(source, amount);
            source.debit(amount);
        }
        if (destination != null) {
            destination.credit(amount);
        }

        // 8. Persist the facts.
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setIdempotencyKey(key);
        tx.setType(type);
        tx.setSourceAccount(source);
        tx.setDestinationAccount(destination);
        tx.setAmount(amount);
        tx.setCurrency(owned.getCurrency());
        tx.setCategory(request.category());
        tx.setDescription(request.description());

        // saveAndFlush: hit the unique (user_id, idempotency_key) constraint now, not at commit
        Transaction saved = transactionRepository.saveAndFlush(tx);

        postLedgerEntries(saved, source, destination, amount);
        fraudService.raiseFlags(saved, owned.getId(), flags);

        auditService.record(userId, AuditAction.TRANSACTION_COMPLETED, "TRANSACTION", saved.getId(),
                type + " " + amount.toPlainString() + " " + saved.getCurrency()
                        + " from=" + saved.getSourceAccountId() + " to=" + saved.getDestinationAccountId());
        outboxService.enqueue("TRANSACTION", saved.getId(), "TransactionCompleted", eventPayload(saved));

        return TransactionResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // Refund
    // ------------------------------------------------------------------

    /**
     * Reverses a TRANSFER in full. Only the receiver of the original transfer can refund it
     * (like a merchant refunding a customer), and only once.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse refundTransaction(Long userId, Long transactionId, String idempotencyKey) {
        validateClientKey(idempotencyKey);

        Optional<TransactionResponse> replay = replayRefundIfProcessed(userId, transactionId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        Transaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

        if (original.getType() != TransactionType.TRANSFER) {
            throw new InvalidTransactionException("Only transfers can be refunded");
        }

        Long payerId = original.getSourceAccountId();
        Long receiverId = original.getDestinationAccountId();

        // Same global lock order as transfers, so "A pays B" racing "B refunds A" cannot deadlock.
        Map<Long, Account> locked = lockInOrder(payerId, receiverId);
        Account payer = locked.get(payerId);
        Account receiver = locked.get(receiverId);

        if (!receiver.isOwnedBy(userId)) {
            throw new ResourceNotFoundException("Transaction not found for this user");
        }

        replay = replayRefundIfProcessed(userId, transactionId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        // A query, not the cached entity: under READ COMMITTED it sees a refund committed while
        // we waited for the locks. The unique constraint on refund_of_transaction_id backs this up.
        if (transactionRepository.findByRefundOfTransactionId(transactionId).isPresent()) {
            throw new InvalidTransactionException("Transaction was already refunded");
        }

        ensureNotFrozen(payer, receiver);

        BigDecimal amount = original.getAmount();
        ensureSufficientBalance(receiver, amount);
        receiver.debit(amount);
        payer.credit(amount);

        Transaction refund = new Transaction();
        refund.setUserId(userId);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setType(TransactionType.REFUND);
        refund.setSourceAccount(receiver);
        refund.setDestinationAccount(payer);
        refund.setAmount(amount);
        refund.setCurrency(original.getCurrency());
        refund.setCategory("REFUND");
        refund.setDescription("Refund of transaction " + transactionId);
        refund.setRefundOfTransactionId(transactionId);

        Transaction saved = transactionRepository.saveAndFlush(refund);
        original.setStatus(TransactionStatus.REFUNDED);

        postLedgerEntries(saved, receiver, payer, amount);

        auditService.record(userId, AuditAction.TRANSACTION_REFUNDED, "TRANSACTION", transactionId,
                "refund transaction " + saved.getId() + " amount " + amount.toPlainString());
        outboxService.enqueue("TRANSACTION", saved.getId(), "TransactionRefunded", eventPayload(saved));

        return TransactionResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // Replay after a lost idempotency race (called by TransactionFacade in a NEW transaction)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findReplay(Long userId, String key, CreateTransactionRequest request) {
        return replayIfProcessed(userId, key, request);
    }

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findRefundReplay(Long userId, Long transactionId, String key) {
        Optional<TransactionResponse> replay = replayRefundIfProcessed(userId, transactionId, key);
        if (replay.isEmpty() && transactionRepository.findByRefundOfTransactionId(transactionId).isPresent()) {
            throw new InvalidTransactionException("Transaction was already refunded");
        }
        return replay;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> search(Long userId, TransactionFilter filter,
                                                    int page, int size, String sortBy, String direction) {
        if (filter.accountId() != null) {
            ownedAccount(userId, filter.accountId());
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new InvalidTransactionException("'from' must be before 'to'");
        }

        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String safeSort = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(sortDirection, safeSort).and(Sort.by(sortDirection, "id")));

        return PageResponse.from(
                transactionRepository.findAll(TransactionSpecifications.matching(userId, filter), pageable),
                TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionById(Long userId, Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .filter(t -> involves(t.getSourceAccount(), userId) || involves(t.getDestinationAccount(), userId))
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found for this user"));
        return TransactionResponse.from(tx);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static void validateClientKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new InvalidTransactionException("Idempotency-Key header must be 1 to 64 characters");
        }
        if (key.startsWith(SYSTEM_KEY_PREFIX)) {
            throw new InvalidTransactionException("Idempotency-Key must not start with '" + SYSTEM_KEY_PREFIX + "'");
        }
    }

    private Optional<TransactionResponse> replayIfProcessed(Long userId, String key, CreateTransactionRequest request) {
        return transactionRepository.findByUserIdAndIdempotencyKey(userId, key).map(prior -> {
            if (!sameRequest(prior, request)) {
                throw new IdempotencyConflictException("Idempotency-Key was already used with a different request");
            }
            return TransactionResponse.from(prior);
        });
    }

    private Optional<TransactionResponse> replayRefundIfProcessed(Long userId, Long transactionId, String key) {
        return transactionRepository.findByUserIdAndIdempotencyKey(userId, key).map(prior -> {
            if (!transactionId.equals(prior.getRefundOfTransactionId())) {
                throw new IdempotencyConflictException("Idempotency-Key was already used with a different request");
            }
            return TransactionResponse.from(prior);
        });
    }

    private static boolean sameRequest(Transaction t, CreateTransactionRequest r) {
        return t.getType() == r.type()
                && t.getAmount().compareTo(r.amount()) == 0
                && Objects.equals(t.getSourceAccountId(), r.sourceAccountId())
                && Objects.equals(t.getDestinationAccountId(), r.destinationAccountId());
    }

    /**
     * Locks the given accounts with SELECT ... FOR UPDATE in ascending id order. Every code path
     * that locks more than one account goes through here, so there is one global lock order and
     * no cycle of "A waits for B while B waits for A" can form.
     */
    private Map<Long, Account> lockInOrder(Long... ids) {
        TreeSet<Long> ordered = new TreeSet<>();
        for (Long id : ids) {
            if (id != null) ordered.add(id);
        }
        Map<Long, Account> locked = new HashMap<>();
        for (Long id : ordered) {
            locked.put(id, accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + id)));
        }
        return locked;
    }

    private Account ownedAccount(Long userId, Long accountId) {
        return accountRepository.findById(accountId)
                .filter(a -> a.isOwnedBy(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Account not found for this user"));
    }

    private static boolean involves(Account account, Long userId) {
        return account != null && account.isOwnedBy(userId);
    }

    private static void ensureNotFrozen(Account... accounts) {
        for (Account a : accounts) {
            if (a != null && a.isFrozen()) {
                throw new AccountFrozenException("Account " + a.getId() + " is frozen");
            }
        }
    }

    private static void ensureSufficientBalance(Account account, BigDecimal amount) {
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance in account " + account.getId());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidTransactionException(message);
        }
    }

    /**
     * Double entry: every transaction posts one DEBIT and one CREDIT of the same amount, so the
     * whole ledger always sums to zero. A null account is the outside world: a top-up debits
     * "external" and credits the wallet; a withdrawal does the reverse.
     */
    private void postLedgerEntries(Transaction tx, Account source, Account destination, BigDecimal amount) {
        ledgerEntryRepository.save(new LedgerEntry(tx, source, EntryDirection.DEBIT, amount));
        ledgerEntryRepository.save(new LedgerEntry(tx, destination, EntryDirection.CREDIT, amount));
    }

    private static Map<String, Object> eventPayload(Transaction t) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", t.getId());
        payload.put("type", t.getType().name());
        payload.put("amount", t.getAmount().toPlainString());
        payload.put("currency", t.getCurrency());
        payload.put("sourceAccountId", t.getSourceAccountId());
        payload.put("destinationAccountId", t.getDestinationAccountId());
        payload.put("refundOfTransactionId", t.getRefundOfTransactionId());
        payload.put("initiatedBy", t.getUserId());
        return payload;
    }
}
