package in.payledger.controller;

import in.payledger.dto.CreateTransactionRequest;
import in.payledger.dto.PageResponse;
import in.payledger.dto.TransactionResponse;
import in.payledger.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(
            TransactionService transactionService) {

        this.transactionService = transactionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(
            @PathVariable Long userId,
            @Valid @RequestBody CreateTransactionRequest request) {

        return transactionService.createTransaction(
                userId,
                request
        );
    }

    @GetMapping("/{transactionId}")
    public TransactionResponse getTransactionById(
            @PathVariable Long userId,
            @PathVariable Long transactionId) {

        return transactionService.getTransactionById(
                userId,
                transactionId
        );
    }

    @GetMapping("/account/{accountId}")
    public PageResponse<TransactionResponse> getTransactionsForAccount(
            @PathVariable Long userId,
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        return transactionService.getTransactionsForAccount(
                userId,
                accountId,
                page,
                size,
                sortBy,
                direction
        );
    }
}