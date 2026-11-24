package in.payledger.audit;

import in.payledger.dto.AuditLogResponse;
import in.payledger.dto.PageResponse;
import in.payledger.entity.AuditLog;
import in.payledger.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Joins the caller's transaction: the audit row commits if and only if the change it
     * describes commits, so the log can never claim something happened that was rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long actorUserId, AuditAction action, String entityType, Object entityId, String details) {
        auditLogRepository.save(new AuditLog(actorUserId, action.name(), entityType,
                entityId == null ? null : String.valueOf(entityId), details));
    }

    /**
     * Commits in its own transaction. Used for attempts that are about to be rejected (and so
     * rolled back), such as a transfer blocked by a fraud rule, which must still leave a trace.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(Long actorUserId, AuditAction action, String entityType, Object entityId, String details) {
        record(actorUserId, action, entityType, entityId, details);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String entityType, String entityId, Long actorUserId, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(auditLogRepository.search(entityType, entityId, actorUserId, pageable),
                AuditLogResponse::from);
    }
}
