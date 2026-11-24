package in.payledger.repository;

import in.payledger.entity.OutboxEvent;
import in.payledger.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of due events. SKIP LOCKED lets several app instances poll the same table
     * at once: each one skips rows another instance is already publishing instead of waiting.
     */
    @Query(value = "select * from outbox_events "
            + "where status = 'PENDING' and next_attempt_at <= :now "
            + "order by id limit :limit for update skip locked", nativeQuery = true)
    List<OutboxEvent> claimDueBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId);
}
