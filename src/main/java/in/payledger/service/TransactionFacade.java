package in.payledger.service;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.TransactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The entry point for money movement from the API. Deliberately NOT @Transactional: it wraps the
 * transactional TransactionService so it can act after a transaction has rolled back.
 *
 * 1. Idempotency race: two requests with the same key both pass the "already processed?" check,
 *    one commits, and the other fails on the UNIQUE(user_id, idempotency_key) constraint. That
 *    loser's transaction is dead, so we look the winner up in a NEW transaction and return it.
 *    The client gets the same answer either way.
 *
 * 2. Deadlock or lock-wait timeout: MySQL aborts one transaction. Everything it did was rolled
 *    back, and the request carries an idempotency key, so retrying it is safe.
 */
@Service
public class TransactionFacade {

    private static final Logger log = LoggerFactory.getLogger(TransactionFacade.class);
    private static final int MAX_ATTEMPTS = 3;

    private final TransactionService transactionService;

    public TransactionFacade(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public TransactionResponse create(Long userId, String key, CreateTransactionRequest request) {
        return withRetry(() -> {
            try {
                return transactionService.createTransaction(userId, key, request);
            } catch (DataIntegrityViolationException e) {
                return transactionService.findReplay(userId, key, request).orElseThrow(() -> e);
            }
        });
    }

    public TransactionResponse refund(Long userId, Long transactionId, String key) {
        return withRetry(() -> {
            try {
                return transactionService.refundTransaction(userId, transactionId, key);
            } catch (DataIntegrityViolationException e) {
                Optional<TransactionResponse> replay = transactionService.findRefundReplay(userId, transactionId, key);
                return replay.orElseThrow(() -> e);
            }
        });
    }

    private TransactionResponse withRetry(Supplier<TransactionResponse> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException e) {   // deadlock, lock wait timeout
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Lock failure on attempt {}/{}, retrying: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleepQuietly(25L * attempt);
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
