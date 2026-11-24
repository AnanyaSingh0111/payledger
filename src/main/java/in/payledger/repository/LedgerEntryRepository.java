package in.payledger.repository;

import in.payledger.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * Extends Repository, not JpaRepository, so there is no update or delete method to call.
 * The ledger is append-only.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry entry);

    List<LedgerEntry> findByTransactionId(Long transactionId);

    Page<LedgerEntry> findByAccountId(Long accountId, Pageable pageable);

    long count();

    @Query("select coalesce(sum(case when e.direction = in.payledger.entity.EntryDirection.CREDIT "
            + "then e.amount else -e.amount end), 0) from LedgerEntry e where e.account.id = :accountId")
    BigDecimal netForAccount(@Param("accountId") Long accountId);

    /** Sum over every entry, including the external side. Double entry means this is always 0. */
    @Query("select coalesce(sum(case when e.direction = in.payledger.entity.EntryDirection.CREDIT "
            + "then e.amount else -e.amount end), 0) from LedgerEntry e")
    BigDecimal netAll();

    @Query("select e.account.id, sum(case when e.direction = in.payledger.entity.EntryDirection.CREDIT "
            + "then e.amount else -e.amount end) from LedgerEntry e "
            + "where e.account is not null group by e.account.id")
    List<Object[]> netPerAccount();

    /** Transactions whose own entries do not net to zero, or that do not have exactly two entries. */
    @Query("select e.transaction.id from LedgerEntry e group by e.transaction.id "
            + "having count(e) <> 2 or sum(case when e.direction = in.payledger.entity.EntryDirection.CREDIT "
            + "then e.amount else -e.amount end) <> 0")
    List<Long> unbalancedTransactionIds();
}
