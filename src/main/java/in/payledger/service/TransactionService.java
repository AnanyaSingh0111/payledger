package in.payledger.service;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.PageResponse;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.EntryDirection;
import in.payledger.entity.LedgerEntry;
import in.payledger.entity.Transaction;
import in.payledger.entity.TransactionType;
import in.payledger.exception.IdempotencyConflictException;
import in.payledger.exception.InsufficientBalanceException;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.LedgerEntryRepository;
import in.payledger.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class TransactionService {

    private static final List<String> SORTABLE_FIELDS =
            List.of("createdAt", "amount", "id");

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository) {

        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    // ------------------------------------------------------------------
    // Create: income, expense, transfer
    // ------------------------------------------------------------------

    @Transactional
    public TransactionResponse createTransaction(
            Long userId,
            String idempotencyKey,
            CreateTransactionRequest request) {

        validateKey(idempotencyKey);

        /*
         * Idempotency:
         * If this key was already successfully processed, return the
         * original transaction instead of moving money again.
         */
        Optional<Transaction> existing =
                transactionRepository.findByUserIdAndIdempotencyKey(
                        userId,
                        idempotencyKey
                );

        if (existing.isPresent()) {

            Transaction prior = existing.get();

            if (!sameRequest(prior, request)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key was already used with a different request"
                );
            }

            return toResponse(prior);
        }

        TransactionType type = request.getType();

        Account sourceAccount = null;
        Account destinationAccount = null;

        // ==============================================================
        // INCOME
        // ==============================================================

        if (type == TransactionType.INCOME) {

            if (request.getSourceAccountId() != null) {
                throw new InvalidTransactionException(
                        "Income cannot have a source account"
                );
            }

            if (request.getDestinationAccountId() == null) {
                throw new InvalidTransactionException(
                        "Income requires a destination account"
                );
            }

            /*
             * The destination account is being modified, so acquire
             * the pessimistic lock before changing its balance.
             */
            destinationAccount =
                    lockUserAccount(
                            userId,
                            request.getDestinationAccountId()
                    );

            destinationAccount.setBalance(
                    destinationAccount.getBalance()
                            .add(request.getAmount())
            );
        }

        // ==============================================================
        // EXPENSE
        // ==============================================================

        else if (type == TransactionType.EXPENSE) {

            if (request.getSourceAccountId() == null) {
                throw new InvalidTransactionException(
                        "Expense requires a source account"
                );
            }

            if (request.getDestinationAccountId() != null) {
                throw new InvalidTransactionException(
                        "Expense cannot have a destination account"
                );
            }

            /*
             * Lock the account before checking/modifying its balance.
             */
            sourceAccount =
                    lockUserAccount(
                            userId,
                            request.getSourceAccountId()
                    );

            ensureSufficientBalance(
                    sourceAccount,
                    request.getAmount()
            );

            sourceAccount.setBalance(
                    sourceAccount.getBalance()
                            .subtract(request.getAmount())
            );
        }

        // ==============================================================
        // TRANSFER
        // ==============================================================

        else if (type == TransactionType.TRANSFER) {

            Long srcId = request.getSourceAccountId();
            Long dstId = request.getDestinationAccountId();

            if (srcId == null || dstId == null) {
                throw new InvalidTransactionException(
                        "Transfer requires both source and destination accounts"
                );
            }

            if (srcId.equals(dstId)) {
                throw new InvalidTransactionException(
                        "Source and destination accounts must be different"
                );
            }

            /*
             * Always acquire locks in ascending account-ID order.
             *
             * A -> B:
             *     lock A
             *     lock B
             *
             * B -> A:
             *     lock A
             *     lock B
             *
             * Therefore there is no circular lock dependency.
             */

            Long firstId = Math.min(srcId, dstId);
            Long secondId = Math.max(srcId, dstId);

            Account first = lockAccount(firstId);
            Account second = lockAccount(secondId);

            sourceAccount =
                    srcId.equals(firstId) ? first : second;

            destinationAccount =
                    srcId.equals(firstId) ? second : first;

            /*
             * Ownership is checked AFTER acquiring the lock.
             */
            if (!sourceAccount.getUser().getId().equals(userId)) {
                throw new ResourceNotFoundException(
                        "Source account not found for this user"
                );
            }

            if (!Objects.equals(
                    sourceAccount.getCurrency(),
                    destinationAccount.getCurrency())) {

                throw new InvalidTransactionException(
                        "Accounts must use the same currency"
                );
            }

            ensureSufficientBalance(
                    sourceAccount,
                    request.getAmount()
            );

            sourceAccount.setBalance(
                    sourceAccount.getBalance()
                            .subtract(request.getAmount())
            );

            destinationAccount.setBalance(
                    destinationAccount.getBalance()
                            .add(request.getAmount())
            );
        }

        // ==============================================================
        // SAVE TRANSACTION
        // ==============================================================

        Transaction transaction = new Transaction();

        transaction.setUserId(userId);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setSourceAccount(sourceAccount);
        transaction.setDestinationAccount(destinationAccount);
        transaction.setType(type);
        transaction.setAmount(request.getAmount());
        transaction.setCategory(request.getCategory());
        transaction.setDescription(request.getDescription());
        transaction.setCreatedAt(LocalDateTime.now());

        Transaction saved =
                transactionRepository.saveAndFlush(transaction);

        /*
         * Ledger entries are created in the same database transaction.
         * If anything fails, the account balance and transaction are
         * rolled back together.
         */
        recordEntries(
                saved,
                type,
                sourceAccount,
                destinationAccount,
                request.getAmount()
        );

        return toResponse(saved);
    }

    // ------------------------------------------------------------------
    // Refund
    // ------------------------------------------------------------------

    @Transactional
    public TransactionResponse refundTransaction(
            Long userId,
            Long transactionId,
            String idempotencyKey) {

        validateKey(idempotencyKey);

        /*
         * Refund idempotency.
         */
        Optional<Transaction> existing =
                transactionRepository.findByUserIdAndIdempotencyKey(
                        userId,
                        idempotencyKey
                );

        if (existing.isPresent()) {

            Transaction prior = existing.get();

            if (!transactionId.equals(
                    prior.getRefundOfTransactionId())) {

                throw new IdempotencyConflictException(
                        "Idempotency-Key was already used with a different request"
                );
            }

            return toResponse(prior);
        }

        Transaction original =
                transactionRepository.findById(transactionId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Transaction not found with id: "
                                                + transactionId
                                ));

        /*
         * Only an original transfer can be refunded.
         */
        if (original.getType() != TransactionType.TRANSFER
                || original.getRefundOfTransactionId() != null) {

            throw new InvalidTransactionException(
                    "Only original transfers can be refunded"
            );
        }

        /*
         * Get account IDs.
         *
         * We do not perform a normal AccountRepository.findById()
         * here before locking.
         */
        Long originalSourceId =
                original.getSourceAccount().getId();

        Long originalDestinationId =
                original.getDestinationAccount().getId();

        /*
         * Lock both accounts in the SAME deterministic order used
         * by normal transfers.
         *
         * This prevents:
         *
         * Transfer A -> B
         * Refund B -> A
         *
         * from creating a lock-order deadlock.
         */
        Long firstId =
                Math.min(
                        originalSourceId,
                        originalDestinationId
                );

        Long secondId =
                Math.max(
                        originalSourceId,
                        originalDestinationId
                );

        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);

        Account originalSource =
                originalSourceId.equals(firstId)
                        ? first
                        : second;

        Account originalDestination =
                originalSourceId.equals(firstId)
                        ? second
                        : first;

        /*
         * Only the receiver of the original transfer can request
         * the refund.
         */
        if (!originalDestination.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException(
                    "Transaction not found for this user"
            );
        }

        /*
         * Database-level unique constraint also protects against
         * duplicate refunds.
         */
        if (transactionRepository.existsByRefundOfTransactionId(
                transactionId)) {

            throw new InvalidTransactionException(
                    "Transaction was already refunded"
            );
        }

        BigDecimal amount = original.getAmount();

        /*
         * The receiver sends the original amount back.
         */
        ensureSufficientBalance(
                originalDestination,
                amount
        );

        originalDestination.setBalance(
                originalDestination.getBalance()
                        .subtract(amount)
        );

        originalSource.setBalance(
                originalSource.getBalance()
                        .add(amount)
        );

        Transaction refund = new Transaction();

        refund.setUserId(userId);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setSourceAccount(originalDestination);
        refund.setDestinationAccount(originalSource);
        refund.setType(TransactionType.TRANSFER);
        refund.setAmount(amount);
        refund.setCategory("REFUND");
        refund.setDescription(
                "Refund of transaction " + transactionId
        );
        refund.setCreatedAt(LocalDateTime.now());
        refund.setRefundOfTransactionId(transactionId);

        /*
         * The unique constraint on refund_of_transaction_id prevents
         * two concurrent refunds of the same original transaction.
         */
        Transaction saved =
                transactionRepository.saveAndFlush(refund);

        recordEntries(
                saved,
                TransactionType.TRANSFER,
                originalDestination,
                originalSource,
                amount
        );

        return toResponse(saved);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getTransactionsForAccount(
            Long userId,
            Long accountId,
            int page,
            int size,
            String sortBy,
            String direction) {

        getUserAccount(userId, accountId);

        Sort.Direction sortDirection =
                "asc".equalsIgnoreCase(direction)
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;

        String safeSort =
                SORTABLE_FIELDS.contains(sortBy)
                        ? sortBy
                        : "createdAt";

        int safeSize =
                Math.min(Math.max(size, 1), 100);

        int safePage =
                Math.max(page, 0);

        Pageable pageable =
                PageRequest.of(
                        safePage,
                        safeSize,
                        Sort.by(sortDirection, safeSort)
                );

        Page<Transaction> transactionPage =
                transactionRepository
                        .findBySourceAccountIdOrDestinationAccountId(
                                accountId,
                                accountId,
                                pageable
                        );

        List<TransactionResponse> content =
                transactionPage.getContent()
                        .stream()
                        .map(this::toResponse)
                        .toList();

        return new PageResponse<>(
                content,
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages(),
                transactionPage.isFirst(),
                transactionPage.isLast()
        );
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionById(
            Long userId,
            Long transactionId) {

        Transaction transaction =
                transactionRepository.findById(transactionId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Transaction not found with id: "
                                                + transactionId
                                ));

        boolean belongsToUser = false;

        if (transaction.getSourceAccount() != null) {

            belongsToUser =
                    transaction.getSourceAccount()
                            .getUser()
                            .getId()
                            .equals(userId);
        }

        if (!belongsToUser
                && transaction.getDestinationAccount() != null) {

            belongsToUser =
                    transaction.getDestinationAccount()
                            .getUser()
                            .getId()
                            .equals(userId);
        }

        if (!belongsToUser) {
            throw new ResourceNotFoundException(
                    "Transaction not found for this user"
            );
        }

        return toResponse(transaction);
    }

    // ------------------------------------------------------------------
    // Validation / account helpers
    // ------------------------------------------------------------------

    private void validateKey(String idempotencyKey) {

        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 64) {

            throw new InvalidTransactionException(
                    "Idempotency-Key must be 1 to 64 characters"
            );
        }
    }

    private Account getUserAccount(
            Long userId,
            Long accountId) {

        return accountRepository.findById(accountId)
                .filter(a ->
                        a.getUser()
                                .getId()
                                .equals(userId))
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Account not found for this user"
                        ));
    }

    /*
     * SELECT ... FOR UPDATE
     *
     * Used when the account belongs to the caller and its balance
     * is going to be modified.
     */
    private Account lockUserAccount(
            Long userId,
            Long accountId) {

        return accountRepository
                .findByIdForUpdate(accountId)
                .filter(a ->
                        a.getUser()
                                .getId()
                                .equals(userId))
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Account not found for this user"
                        ));
    }

    /*
     * SELECT ... FOR UPDATE
     *
     * Used for transfers/refunds where the account may belong to
     * another user.
     *
     * Ownership is deliberately checked separately after locking.
     */
    private Account lockAccount(Long accountId) {

        return accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Account not found with id: " + accountId
                        ));
    }

    private void ensureSufficientBalance(
            Account account,
            BigDecimal amount) {

        if (account.getBalance().compareTo(amount) < 0) {

            throw new InsufficientBalanceException(
                    "Insufficient balance in account: "
                            + account.getId()
            );
        }
    }

    // ------------------------------------------------------------------
    // Idempotency comparison
    // ------------------------------------------------------------------

    private boolean sameRequest(
            Transaction t,
            CreateTransactionRequest r) {

        /*
         * A refund uses the idempotency key for a different operation,
         * so it cannot be replayed as a normal transaction request.
         */
        if (t.getRefundOfTransactionId() != null) {
            return false;
        }

        Long src =
                t.getSourceAccount() != null
                        ? t.getSourceAccount().getId()
                        : null;

        Long dst =
                t.getDestinationAccount() != null
                        ? t.getDestinationAccount().getId()
                        : null;

        return t.getType() == r.getType()
                && t.getAmount().compareTo(r.getAmount()) == 0
                && Objects.equals(
                src,
                r.getSourceAccountId()
        )
                && Objects.equals(
                dst,
                r.getDestinationAccountId()
        );
    }

    // ------------------------------------------------------------------
    // Double-entry ledger
    // ------------------------------------------------------------------

    private void recordEntries(
            Transaction tx,
            TransactionType type,
            Account src,
            Account dst,
            BigDecimal amount) {

        switch (type) {

            case INCOME -> {

                ledgerEntryRepository.save(
                        new LedgerEntry(
                                tx,
                                null,
                                EntryDirection.DEBIT,
                                amount
                        )
                );

                ledgerEntryRepository.save(
                        new LedgerEntry(
                                tx,
                                dst,
                                EntryDirection.CREDIT,
                                amount
                        )
                );
            }

            case EXPENSE -> {

                ledgerEntryRepository.save(
                        new LedgerEntry(
                                tx,
                                src,
                                EntryDirection.DEBIT,
                                amount
                        )
                );

                ledgerEntryRepository.save(
                        new LedgerEntry(
                                tx,
                                null,
                                EntryDirection.CREDIT,
                                amount
                        )
                );
            }

            case TRANSFER -> {

                ledgerEntryRepository.save(
                        new LedgerEntry(
                                tx,
                                src,
                                EntryDirection.DEBIT,
                                amount
                        )
                );

                ledgerEntryRepository.save(
                        new LedgerEntry(
                                tx,
                                dst,
                                EntryDirection.CREDIT,
                                amount
                        )
                );
            }
        }
    }

    // ------------------------------------------------------------------
    // Response mapping
    // ------------------------------------------------------------------

    private TransactionResponse toResponse(
            Transaction transaction) {

        return new TransactionResponse(
                transaction.getId(),

                transaction.getSourceAccount() != null
                        ? transaction.getSourceAccount().getId()
                        : null,

                transaction.getDestinationAccount() != null
                        ? transaction.getDestinationAccount().getId()
                        : null,

                transaction.getType(),
                transaction.getAmount(),
                transaction.getCategory(),
                transaction.getDescription(),
                transaction.getCreatedAt(),
                transaction.getRefundOfTransactionId()
        );
    }
}