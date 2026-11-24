package in.payledger.service;

import in.payledger.audit.AuditAction;
import in.payledger.audit.AuditService;
import in.payledger.dto.CreateUserRequest;
import in.payledger.dto.PageResponse;
import in.payledger.dto.UserResponse;
import in.payledger.entity.Role;
import in.payledger.entity.User;
import in.payledger.entity.UserStatus;
import in.payledger.exception.InvalidTransactionException;
import in.payledger.exception.ResourceAlreadyExistsException;
import in.payledger.exception.ResourceNotFoundException;
import in.payledger.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /** Emails are compared case-insensitively: "A@x.com" and "a@x.com" are the same person. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public UserResponse register(CreateUserRequest request) {
        return UserResponse.from(create(request.name(), request.email(), request.password(), Role.USER));
    }

    @Transactional
    public User create(String name, String email, String rawPassword, Role role) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new ResourceAlreadyExistsException("Email already exists");
        }
        User user = new User();
        user.setName(name.trim());
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        try {
            // flush now so a concurrent registration with the same email fails here, as a 409
            User saved = userRepository.saveAndFlush(user);
            auditService.record(saved.getId(), AuditAction.USER_REGISTERED, "USER", saved.getId(), role.name());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new ResourceAlreadyExistsException("Email already exists");
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return UserResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> getAllUsers(int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("id"));
        return PageResponse.from(userRepository.findAll(pageable), UserResponse::from);
    }

    /**
     * Users are disabled, never deleted: their transactions and ledger entries must stay intact.
     * A disabled user cannot log in, and their existing JWTs stop working on the next request.
     */
    @Transactional
    public UserResponse setStatus(Long adminId, Long userId, UserStatus status, String reason) {
        if (adminId.equals(userId) && status == UserStatus.DISABLED) {
            throw new InvalidTransactionException("Admins cannot disable themselves");
        }
        User user = find(userId);
        if (user.getStatus() != status) {
            user.setStatus(status);
            auditService.record(adminId, status == UserStatus.DISABLED ? AuditAction.USER_DISABLED : AuditAction.USER_ENABLED,
                    "USER", userId, reason);
        }
        return UserResponse.from(user);
    }

    private User find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }
}
