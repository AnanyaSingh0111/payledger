package in.payledger.repository;

import in.payledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

// Extends Repository, not JpaRepository, so there is no delete or update method to call
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry entry);

    List<LedgerEntry> findByTransactionId(Long transactionId);

    @Query("select coalesce(sum(case when e.direction = in.payledger.entity.EntryDirection.CREDIT "
            + "then e.amount else -e.amount end), 0) from LedgerEntry e where e.account.id = :accountId")
    BigDecimal netForAccount(@Param("accountId") Long accountId);

    @Query("select coalesce(sum(case when e.direction = in.payledger.entity.EntryDirection.CREDIT "
            + "then e.amount else -e.amount end), 0) from LedgerEntry e")
    BigDecimal netAll();
}