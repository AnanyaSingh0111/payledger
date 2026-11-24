package in.payledger.fraud;

import in.payledger.audit.AuditAction;
import in.payledger.audit.AuditService;
import in.payledger.dto.FraudFlagResponse;
import in.payledger.dto.PageResponse;
import in.payledger.dto.ReviewFraudFlagRequest;
import in.payledger.entity.FraudFlag;
import in.payledger.entity.FraudFlagStatus;
import in.payledger.entity.Transaction;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.exception.TransactionRejectedException;
import in.payledger.outbox.OutboxService;
import in.payledger.repository.FraudFlagRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FraudService {

    private final List<FraudRule> rules;   // every FraudRule bean, in @Order
    private final FraudFlagRepository fraudFlagRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public FraudService(List<FraudRule> rules,
                        FraudFlagRepository fraudFlagRepository,
                        AuditService auditService,
                        OutboxService outboxService) {
        this.rules = rules;
        this.fraudFlagRepository = fraudFlagRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    /**
     * Runs every rule. If any rule BLOCKS, the attempt is audited in its own transaction (the
     * caller's transaction is about to roll back) and a TransactionRejectedException is thrown.
     *
     * @return the FLAG verdicts, to be stored once the transaction has an id
     */
    public List<FraudVerdict> assess(FraudContext ctx) {
        List<FraudVerdict> verdicts = rules.stream()
                .map(rule -> rule.evaluate(ctx))
                .flatMap(Optional::stream)
                .toList();

        Optional<FraudVerdict> block = verdicts.stream()
                .filter(v -> v.action() == FraudVerdict.Action.BLOCK)
                .findFirst();

        if (block.isPresent()) {
            FraudVerdict v = block.get();
            auditService.recordIndependently(ctx.userId(), AuditAction.TRANSACTION_BLOCKED, "ACCOUNT",
                    ctx.source() != null ? ctx.source().getId() : null,
                    v.ruleName() + ": " + v.reason() + " (type=" + ctx.type() + ", amount=" + ctx.amount() + ")");
            throw new TransactionRejectedException(v.reason());
        }

        return verdicts.stream().filter(v -> v.action() == FraudVerdict.Action.FLAG).toList();
    }

    /** Persists flags in the caller's transaction, so a flag never points at a rolled-back transaction. */
    @Transactional
    public void raiseFlags(Transaction tx, Long accountId, List<FraudVerdict> flags) {
        for (FraudVerdict v : flags) {
            FraudFlag flag = fraudFlagRepository.save(new FraudFlag(tx.getId(), accountId, v.ruleName(), v.reason()));
            auditService.record(tx.getUserId(), AuditAction.FRAUD_FLAG_RAISED, "TRANSACTION", tx.getId(),
                    v.ruleName() + ": " + v.reason());
            outboxService.enqueue("FRAUD_FLAG", flag.getId(), "FraudFlagRaised", Map.of(
                    "flagId", flag.getId(),
                    "transactionId", tx.getId(),
                    "rule", v.ruleName(),
                    "reason", v.reason()));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<FraudFlagResponse> list(FraudFlagStatus status, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        var result = status == null
                ? fraudFlagRepository.findAll(pageable)
                : fraudFlagRepository.findByStatus(status, pageable);
        return PageResponse.from(result, FraudFlagResponse::from);
    }

    @Transactional
    public FraudFlagResponse review(Long adminId, Long flagId, ReviewFraudFlagRequest request) {
        if (request.outcome() == FraudFlagStatus.OPEN) {
            throw new InvalidTransactionException("Outcome must be CLEARED or CONFIRMED");
        }
        FraudFlag flag = fraudFlagRepository.findById(flagId)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud flag not found with id: " + flagId));
        if (flag.getStatus() != FraudFlagStatus.OPEN) {
            throw new InvalidTransactionException("Fraud flag was already reviewed");
        }
        flag.review(request.outcome(), adminId, request.note());
        auditService.record(adminId, AuditAction.FRAUD_FLAG_REVIEWED, "FRAUD_FLAG", flagId,
                request.outcome() + (request.note() != null ? ": " + request.note() : ""));
        return FraudFlagResponse.from(flag);
    }
}
