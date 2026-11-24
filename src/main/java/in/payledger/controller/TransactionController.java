package in.payledger.controller;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.PageResponse;
import in.payledger.dto.TransactionFilter;
import in.payledger.dto.TransactionResponse;
import in.payledger.entity.TransactionStatus;
import in.payledger.entity.TransactionType;
import in.payledger.service.TransactionFacade;
import in.payledger.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/users/{userId}/transactions")
@PreAuthorize("#userId == authentication.principal")
@Tag(name = "Transactions", description = "Top-up, withdraw, transfer, refund and history")
public class TransactionController {

    private final TransactionFacade transactionFacade;
    private final TransactionService transactionService;

    public TransactionController(TransactionFacade transactionFacade, TransactionService transactionService) {
        this.transactionFacade = transactionFacade;
        this.transactionService = transactionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a TOP_UP, WITHDRAWAL or TRANSFER",
            description = "Retrying with the same Idempotency-Key returns the original transaction and moves no money.")
    public TransactionResponse createTransaction(
            @PathVariable Long userId,
            @Parameter(description = "Unique per payment, 1 to 64 characters (a UUID is ideal)")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        return transactionFacade.create(userId, idempotencyKey, request);
    }

    @PostMapping("/{transactionId}/refund")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Refund a transfer you received", description = "Full refund, at most once per transfer.")
    public TransactionResponse refundTransaction(
            @PathVariable Long userId,
            @PathVariable Long transactionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return transactionFacade.refund(userId, transactionId, idempotencyKey);
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get one transaction you sent or received")
    public TransactionResponse getTransactionById(@PathVariable Long userId, @PathVariable Long transactionId) {
        return transactionService.getTransactionById(userId, transactionId);
    }

    @GetMapping
    @Operation(summary = "Transaction history with filters and pagination",
            description = "Dates are ISO-8601, e.g. 2026-01-31T00:00:00. 'to' is exclusive. Max page size 100.")
    public PageResponse<TransactionResponse> search(
            @PathVariable Long userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return transactionService.search(userId,
                new TransactionFilter(accountId, type, status, from, to, minAmount, maxAmount),
                page, size, sortBy, direction);
    }

    /** Kept for backwards compatibility; same as GET /transactions?accountId={accountId}. */
    @GetMapping("/account/{accountId}")
    @Operation(summary = "History for one account (same as ?accountId=)")
    public PageResponse<TransactionResponse> getTransactionsForAccount(
            @PathVariable Long userId,
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return transactionService.search(userId, TransactionFilter.forAccount(accountId), page, size, sortBy, direction);
    }
}
