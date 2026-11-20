package in.payledger.repository;

import in.payledger.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long> {

    Page<Transaction> findBySourceAccountIdOrDestinationAccountId(
            Long sourceAccountId,
            Long destinationAccountId,
            Pageable pageable
    );
    Optional<Transaction> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    boolean existsByRefundOfTransactionId(Long refundOfTransactionId);
}