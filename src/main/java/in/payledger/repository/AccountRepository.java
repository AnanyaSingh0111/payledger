package in.payledger.repository;

import in.payledger.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * SELECT ... FOR UPDATE. A locking read always sees the latest committed row (not the
     * transaction's snapshot), and blocks other writers until this transaction ends.
     * The owner is fetched in the same query because ownership is checked right after locking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a join fetch a.user where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    List<Account> findByUserIdOrderByIdAsc(Long userId);

    /** A user has at most one wallet per currency (UNIQUE(user_id, currency) in the database). */
    boolean existsByUserIdAndCurrency(Long userId, String currency);
}
