package in.payledger.repository;

import in.payledger.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Append-only like the ledger: no update or delete method exists. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog log);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, String entityId);

    @Query("select a from AuditLog a where (:entityType is null or a.entityType = :entityType) "
            + "and (:entityId is null or a.entityId = :entityId) "
            + "and (:actorUserId is null or a.actorUserId = :actorUserId)")
    Page<AuditLog> search(@Param("entityType") String entityType,
                          @Param("entityId") String entityId,
                          @Param("actorUserId") Long actorUserId,
                          Pageable pageable);
}
