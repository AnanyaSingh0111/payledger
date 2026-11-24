package in.payledger.repository;

import in.payledger.entity.Transaction;
import in.payledger.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Transaction> findByRefundOfTransactionId(Long refundOfTransactionId);

    /** Money that left an account since a point in time (used by the daily-limit rule). */
    @Query("select coalesce(sum(t.amount), 0) from Transaction t "
            + "where t.sourceAccount.id = :accountId and t.type in :types and t.createdAt >= :since")
    BigDecimal sumOutgoingSince(@Param("accountId") Long accountId,
                                @Param("types") Collection<TransactionType> types,
                                @Param("since") LocalDateTime since);

    @Query("select count(t) from Transaction t "
            + "where t.sourceAccount.id = :accountId and t.type in :types and t.createdAt >= :since")
    long countOutgoingSince(@Param("accountId") Long accountId,
                            @Param("types") Collection<TransactionType> types,
                            @Param("since") LocalDateTime since);
}
