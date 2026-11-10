package in.payledger.service;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.Account;
import in.payledger.entity.Transaction;
import in.payledger.entity.TransactionType;
import in.payledger.exception.IdempotencyConflictException;
import in.payledger.exception.InsufficientBalanceException;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import in.payledger.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository) {

        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }
    @Transactional
    public TransactionResponse createTransaction(
            Long userId,
            String idempotencyKey,
            CreateTransactionRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new InvalidTransactionException(
                    "Idempotency-Key must be 1 to 64 characters");
        }

        // 1. Replay: same key seen before -> return the stored result, move no money
        Optional<Transaction> existing =
                transactionRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);

        if (existing.isPresent()) {
            Transaction prior = existing.get();
            if (!sameRequest(prior, request)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key was already used with a different request");
            }
            return toResponse(prior);
        }

        TransactionType type = request.getType();
        Account sourceAccount = null;
        Account destinationAccount = null;

        if (type == TransactionType.INCOME) {

            if (request.getSourceAccountId() != null)
                throw new InvalidTransactionException("Income cannot have a source account");
            if (request.getDestinationAccountId() == null)
                throw new InvalidTransactionException("Income requires a destination account");

            destinationAccount = lockUserAccount(userId, request.getDestinationAccountId());
            destinationAccount.setBalance(
                    destinationAccount.getBalance().add(request.getAmount()));

        } else if (type == TransactionType.EXPENSE) {

            if (request.getSourceAccountId() == null)
                throw new InvalidTransactionException("Expense requires a source account");
            if (request.getDestinationAccountId() != null)
                throw new InvalidTransactionException("Expense cannot have a destination account");

            sourceAccount = lockUserAccount(userId, request.getSourceAccountId());
            ensureSufficientBalance(sourceAccount, request.getAmount());
            sourceAccount.setBalance(
                    sourceAccount.getBalance().subtract(request.getAmount()));

        } else if (type == TransactionType.TRANSFER) {

            Long srcId = request.getSourceAccountId();
            Long dstId = request.getDestinationAccountId();

            if (srcId == null || dstId == null)
                throw new InvalidTransactionException(
                        "Transfer requires both source and destination accounts");
            if (srcId.equals(dstId))
                throw new InvalidTransactionException(
                        "Source and destination accounts must be different");

            // Always lock the lower id first. Two opposite transfers (A->B and B->A)
            // then queue up instead of each holding one lock and waiting on the other.
            Long firstId = Math.min(srcId, dstId);
            Long secondId = Math.max(srcId, dstId);

            Account first = lockUserAccount(userId, firstId);
            Account second = lockUserAccount(userId, secondId);

            sourceAccount = srcId.equals(firstId) ? first : second;
            destinationAccount = srcId.equals(firstId) ? second : first;

            ensureSufficientBalance(sourceAccount, request.getAmount());
            sourceAccount.setBalance(
                    sourceAccount.getBalance().subtract(request.getAmount()));
            destinationAccount.setBalance(
                    destinationAccount.getBalance().add(request.getAmount()));
        }

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

        // saveAndFlush so a duplicate key fails here, inside the transaction, and rolls
        // back the balance changes with it
        return toResponse(transactionRepository.saveAndFlush(transaction));
    }

    private Account lockUserAccount(Long userId, Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .filter(a -> a.getUser().getId().equals(userId))
                .orElseThrow(() ->
                        new ResourceNotFoundException("Account not found for this user"));
    }

    private boolean sameRequest(Transaction t, CreateTransactionRequest r) {
        Long src = t.getSourceAccount() != null ? t.getSourceAccount().getId() : null;
        Long dst = t.getDestinationAccount() != null ? t.getDestinationAccount().getId() : null;

        return t.getType() == r.getType()
                && t.getAmount().compareTo(r.getAmount()) == 0
                && java.util.Objects.equals(src, r.getSourceAccountId())
                && java.util.Objects.equals(dst, r.getDestinationAccountId());
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getTransactionsForAccount(
            Long userId,
            Long accountId,
            int page,
            int size,
            String sortBy,
            String direction) {



        // 2. Decide sorting direction
        Sort.Direction sortDirection =
                direction.equalsIgnoreCase("asc")
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;

        // 3. Create pagination + sorting request
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(sortDirection, sortBy)
        );

        // 4. Fetch requested page
        Page<Transaction> transactionPage =
                transactionRepository
                        .findBySourceAccountIdOrDestinationAccountId(
                                accountId,
                                accountId,
                                pageable
                        );

        // 5. Convert entities → DTOs
        List<TransactionResponse> content =
                transactionPage
                        .getContent()
                        .stream()
                        .map(this::toResponse)
                        .toList();

        // 6. Return our own API response
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
                transaction.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionById(
            Long userId,
            Long transactionId) {

        Transaction transaction = transactionRepository
                .findById(transactionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Transaction not found with id: " + transactionId
                        )
                );

        boolean belongsToUser = false;

        if (transaction.getSourceAccount() != null) {
            belongsToUser = transaction
                    .getSourceAccount()
                    .getUser()
                    .getId()
                    .equals(userId);
        }

        if (!belongsToUser && transaction.getDestinationAccount() != null) {
            belongsToUser = transaction
                    .getDestinationAccount()
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
}