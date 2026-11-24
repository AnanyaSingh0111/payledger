package in.payledger.controller;

import in.payledger.audit.AuditService;
import in.payledger.dto.AccountResponse;
import in.payledger.dto.AuditLogResponse;
import in.payledger.dto.FraudFlagResponse;
import in.payledger.dto.OutboxStatsResponse;
import in.payledger.dto.PageResponse;
import in.payledger.dto.ReconciliationReport;
import in.payledger.dto.ReviewFraudFlagRequest;
import in.payledger.dto.StatusChangeRequest;
import in.payledger.dto.UserResponse;
import in.payledger.entity.AccountStatus;
import in.payledger.entity.FraudFlagStatus;
import in.payledger.entity.UserStatus;
import in.payledger.fraud.FraudService;
import in.payledger.outbox.OutboxRelay;
import in.payledger.service.AccountService;
import in.payledger.service.ReconciliationService;
import in.payledger.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Reconciliation, fraud review, freezing, audit trail")
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final FraudService fraudService;
    private final AccountService accountService;
    private final UserService userService;
    private final AuditService auditService;
    private final OutboxRelay outboxRelay;

    public AdminController(ReconciliationService reconciliationService,
                           FraudService fraudService,
                           AccountService accountService,
                           UserService userService,
                           AuditService auditService,
                           OutboxRelay outboxRelay) {
        this.reconciliationService = reconciliationService;
        this.fraudService = fraudService;
        this.accountService = accountService;
        this.userService = userService;
        this.auditService = auditService;
        this.outboxRelay = outboxRelay;
    }

    @PostMapping("/reconciliation/run")
    @Operation(summary = "Check every balance against the ledger now")
    public ReconciliationReport runReconciliation(@AuthenticationPrincipal Long adminId) {
        return reconciliationService.run(adminId);
    }

    @GetMapping("/fraud-flags")
    @Operation(summary = "Flagged transactions (default: OPEN ones)")
    public PageResponse<FraudFlagResponse> fraudFlags(@RequestParam(defaultValue = "OPEN") FraudFlagStatus status,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return fraudService.list(status, page, size);
    }

    @PostMapping("/fraud-flags/{flagId}/review")
    @Operation(summary = "Mark a flag CLEARED (false positive) or CONFIRMED")
    public FraudFlagResponse reviewFlag(@AuthenticationPrincipal Long adminId,
                                        @PathVariable Long flagId,
                                        @Valid @RequestBody ReviewFraudFlagRequest request) {
        return fraudService.review(adminId, flagId, request);
    }

    @PostMapping("/accounts/{accountId}/freeze")
    @Operation(summary = "Freeze a wallet: it can no longer send or receive money")
    public AccountResponse freeze(@AuthenticationPrincipal Long adminId,
                                  @PathVariable Long accountId,
                                  @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return accountService.setStatus(adminId, accountId, AccountStatus.FROZEN, reason(request));
    }

    @PostMapping("/accounts/{accountId}/unfreeze")
    @Operation(summary = "Unfreeze a wallet")
    public AccountResponse unfreeze(@AuthenticationPrincipal Long adminId,
                                    @PathVariable Long accountId,
                                    @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return accountService.setStatus(adminId, accountId, AccountStatus.ACTIVE, reason(request));
    }

    @PostMapping("/users/{userId}/disable")
    @Operation(summary = "Disable a user: login fails and existing tokens stop working immediately")
    public UserResponse disableUser(@AuthenticationPrincipal Long adminId,
                                    @PathVariable Long userId,
                                    @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return userService.setStatus(adminId, userId, UserStatus.DISABLED, reason(request));
    }

    @PostMapping("/users/{userId}/enable")
    @Operation(summary = "Re-enable a user")
    public UserResponse enableUser(@AuthenticationPrincipal Long adminId,
                                   @PathVariable Long userId,
                                   @Valid @RequestBody(required = false) StatusChangeRequest request) {
        return userService.setStatus(adminId, userId, UserStatus.ACTIVE, reason(request));
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "Audit trail, newest first, filterable by entity or actor")
    public PageResponse<AuditLogResponse> auditLogs(@RequestParam(required = false) String entityType,
                                                    @RequestParam(required = false) String entityId,
                                                    @RequestParam(required = false) Long actorUserId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return auditService.search(entityType, entityId, actorUserId, page, size);
    }

    @GetMapping("/outbox/stats")
    @Operation(summary = "Outbox backlog: pending, published and failed events")
    public OutboxStatsResponse outboxStats() {
        return outboxRelay.stats();
    }

    private static String reason(StatusChangeRequest request) {
        return request == null ? null : request.reason();
    }
}
