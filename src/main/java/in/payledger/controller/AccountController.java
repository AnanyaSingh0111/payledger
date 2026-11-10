package in.payledger.controller;

import in.payledger.dto.AccountResponse;
import in.payledger.dto.CreateAccountRequest;
import in.payledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/accounts")
@PreAuthorize("#userId == authentication.principal")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            @PathVariable Long userId,
            @Valid @RequestBody CreateAccountRequest request) {

        return accountService.createAccount(
                userId,
                request
        );
    }

    @GetMapping
    public List<AccountResponse> getAccountsForUser(
            @PathVariable Long userId) {

        return accountService.getAccountsForUser(userId);
    }
}