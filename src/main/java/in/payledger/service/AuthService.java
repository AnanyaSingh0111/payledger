package in.payledger.service;

import in.payledger.dto.AuthResponse;
import in.payledger.dto.LoginRequest;
import in.payledger.entity.User;
import in.payledger.exception.UserDisabledException;
import in.payledger.repository.UserRepository;
import in.payledger.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** A real BCrypt hash, so unknown emails cost the same time as wrong passwords (no timing oracle). */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(UserService.normalizeEmail(request.email())).orElse(null);

        // Always run one BCrypt comparison, and use the same message for "no such user" and
        // "wrong password", so neither the response nor its timing reveals which emails exist.
        boolean matches = passwordEncoder.matches(request.password(),
                user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !matches) {
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!user.isActive()) {
            throw new UserDisabledException("User account is disabled");
        }
        return new AuthResponse(jwtService.generateToken(user), jwtService.getExpirationSeconds(),
                user.getId(), user.getRole());
    }
}
