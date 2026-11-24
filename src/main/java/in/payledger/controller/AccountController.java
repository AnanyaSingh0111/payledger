package in.payledger.controller;

import in.payledger.dto.AccountDetailResponse;
import in.payledger.dto.AccountResponse;
import in.payledger.dto.CreateAccountRequest;
import in.payledger.dto.LedgerEntryResponse;
import in.payledger.dto.PageResponse;
import in.payledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/accounts")
@PreAuthorize("#userId == authentication.principal")
@Tag(name = "Accounts", description = "Wallets and their ledger statements")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a wallet", description = "An initial balance is recorded in the ledger as a TOP_UP.")
    public AccountResponse createAccount(@PathVariable Long userId, @Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(userId, request);
    }

    @GetMapping
    @Operation(summary = "List your wallets")
    public List<AccountResponse> getAccountsForUser(@PathVariable Long userId) {
        return accountService.getAccountsForUser(userId);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "One wallet, with its balance recomputed from the ledger")
    public AccountDetailResponse getAccount(@PathVariable Long userId, @PathVariable Long accountId) {
        return accountService.getAccount(userId, accountId);
    }

    @GetMapping("/{accountId}/statement")
    @Operation(summary = "Ledger entries for a wallet, newest first")
    public PageResponse<LedgerEntryResponse> getStatement(@PathVariable Long userId,
                                                          @PathVariable Long accountId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return accountService.getStatement(userId, accountId, page, size);
    }
}
