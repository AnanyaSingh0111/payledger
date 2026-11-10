package in.payledger.service;

import in.payledger.dto.AccountResponse;
import in.payledger.dto.CreateAccountRequest;
import in.payledger.entity.Account;
import in.payledger.entity.User;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.repository.AccountRepository;
import in.payledger.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(
            AccountRepository accountRepository,
            UserRepository userRepository) {

        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    public AccountResponse createAccount(
            Long userId,
            CreateAccountRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found with id: " + userId
                        )
                );

        Account account = new Account();

        account.setUser(user);
        account.setName(request.getName());
        account.setType(request.getType());
        account.setBalance(request.getInitialBalance());
        account.setCurrency("INR");
        account.setCreatedAt(LocalDateTime.now());

        Account savedAccount =
                accountRepository.save(account);

        return toResponse(savedAccount);
    }

    public List<AccountResponse> getAccountsForUser(
            Long userId) {

        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException(
                    "User not found with id: " + userId
            );
        }

        return accountRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public AccountResponse getAccountById(Long accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Account not found with id: "
                                        + accountId
                        )
                );

        return toResponse(account);
    }

    private AccountResponse toResponse(Account account) {

        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt()
        );
    }
}